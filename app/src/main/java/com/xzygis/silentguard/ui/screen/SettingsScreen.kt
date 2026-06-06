package com.xzygis.silentguard.ui.screen

import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.xzygis.silentguard.config.AppConfig
import com.xzygis.silentguard.config.MonitorConfig
import com.xzygis.silentguard.location.AmapCoordinateConverter
import com.xzygis.silentguard.location.AmapReverseGeocoder
import com.xzygis.silentguard.mail.MailSender
import com.xzygis.silentguard.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    appConfig: AppConfig,
    mailSender: MailSender,
    isGuarding: Boolean = false,
    onToggleGuarding: (Boolean) -> Unit = {}
) {
    val config by appConfig.configFlow.collectAsState(initial = MonitorConfig())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val appVersionName = remember(context) {
        context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName ?: "未知"
    }

    var smtpHost by remember(config.smtpHost) { mutableStateOf(config.smtpHost) }
    var smtpPort by remember(config.smtpPort) { mutableStateOf(config.smtpPort.toString()) }
    var senderEmail by remember(config.senderEmail) { mutableStateOf(config.senderEmail) }
    var senderPassword by remember(config.senderPassword) { mutableStateOf(config.senderPassword) }
    var recipientEmail by remember(config.recipientEmail) { mutableStateOf(config.recipientEmail) }
    var locationInterval by remember(config.locationIntervalMinutes) { mutableStateOf(config.locationIntervalMinutes.toString()) }
    var emailInterval by remember(config.emailIntervalMinutes) { mutableStateOf(config.emailIntervalMinutes.toString()) }
    var useHighAccuracy by remember(config.useHighAccuracy) { mutableStateOf(config.useHighAccuracy) }
    var amapWebApiKey by remember(config.amapWebApiKey) { mutableStateOf(config.amapWebApiKey) }

    // 标记 config 是否已从 DataStore 加载完成（非默认空值）
    val configLoaded = config.smtpHost.isNotEmpty() ||
            config.senderEmail.isNotEmpty() ||
            config.recipientEmail.isNotEmpty() ||
            config.amapWebApiKey.isNotEmpty() ||
            config.smtpPort != 465 ||
            config.isGuardingEnabled

    // 自动保存：任意字段变化后 800ms 自动持久化
    // 使用各字段值作为 key，仅在用户实际编辑后触发
    LaunchedEffect(
        smtpHost, smtpPort, senderEmail, senderPassword, recipientEmail,
        locationInterval, emailInterval, useHighAccuracy, amapWebApiKey
    ) {
        // config 未加载完毕时不保存，避免用空值覆盖
        if (!configLoaded && smtpHost.isBlank() && senderEmail.isBlank()) return@LaunchedEffect

        delay(800)
        val newConfig = MonitorConfig(
            smtpHost = smtpHost.trim(),
            smtpPort = smtpPort.trim().toIntOrNull() ?: 465,
            senderEmail = senderEmail.trim(),
            senderPassword = senderPassword.trim(),
            recipientEmail = recipientEmail.trim(),
            locationIntervalMinutes = locationInterval.trim().toIntOrNull() ?: 5,
            emailIntervalMinutes = emailInterval.trim().toIntOrNull() ?: 60,
            isGuardingEnabled = config.isGuardingEnabled,
            useHighAccuracy = useHighAccuracy,
            amapWebApiKey = amapWebApiKey.trim()
        )
        appConfig.saveConfig(newConfig)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(4.dp))

        SetupSummaryCard(
            isGuarding = isGuarding,
            mailReady = senderEmail.isNotBlank() &&
                    senderPassword.isNotBlank() &&
                    recipientEmail.isNotBlank(),
            mapReady = amapWebApiKey.isNotBlank()
        )

        // 守护开关
        SettingsSection(title = "守护服务") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "启动守护",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isGuarding) "短信与位置记录运行中" else "守护已关闭",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isGuarding,
                    onCheckedChange = { onToggleGuarding(it) },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = Success
                    )
                )
            }
        }

        // 邮件配置
        SettingsSection(title = "邮件服务") {
            SettingsTextField(
                value = smtpHost,
                onValueChange = { smtpHost = it },
                label = "SMTP 服务器",
                placeholder = "smtp.feishu.cn"
            )
            SettingsTextField(
                value = smtpPort,
                onValueChange = { smtpPort = it },
                label = "端口",
                placeholder = "465",
                keyboardType = KeyboardType.Number
            )
            SettingsTextField(
                value = senderEmail,
                onValueChange = { senderEmail = it },
                label = "发送邮箱",
                keyboardType = KeyboardType.Email
            )
            SettingsTextField(
                value = senderPassword,
                onValueChange = { senderPassword = it },
                label = "授权码",
                isPassword = true
            )
            SettingsTextField(
                value = recipientEmail,
                onValueChange = { recipientEmail = it },
                label = "接收邮箱",
                keyboardType = KeyboardType.Email
            )
        }

        // 记录配置
        SettingsSection(title = "记录参数") {
            SettingsTextField(
                value = locationInterval,
                onValueChange = { locationInterval = it },
                label = "位置记录间隔（分钟）",
                placeholder = "5",
                keyboardType = KeyboardType.Number
            )
            SettingsTextField(
                value = emailInterval,
                onValueChange = { emailInterval = it },
                label = "邮件发送间隔（分钟）",
                placeholder = "60",
                keyboardType = KeyboardType.Number
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "高精度定位",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (useHighAccuracy) "使用 GPS，精度高但更耗电" else "使用基站/WiFi，省电但精度较低",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = useHighAccuracy,
                    onCheckedChange = { useHighAccuracy = it },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = Success
                    )
                )
            }
        }

        // 地图配置
        SettingsSection(title = "地图邮件") {
            SettingsTextField(
                value = amapWebApiKey,
                onValueChange = { amapWebApiKey = it },
                label = "高德 Web API Key",
                placeholder = "用于邮件中的静态地图"
            )
            Text(
                text = "配置后，位置邮件将包含带路径标记的地图图片。需在高德开放平台申请「Web服务」类型的 Key。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 测试邮件
        OutlinedButton(
            onClick = {
                scope.launch {
                    Toast.makeText(context, "正在发送测试邮件…", Toast.LENGTH_SHORT).show()
                    val success = mailSender.sendMail(
                        subject = "[测试] SilentGuard 测试邮件",
                        body = buildTestMailBody(context, useHighAccuracy, amapWebApiKey.trim())
                    )
                    if (success) {
                        Toast.makeText(context, "测试邮件发送成功", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "发送失败，请检查配置", Toast.LENGTH_LONG).show()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(
                text = "发送测试邮件",
                modifier = Modifier.padding(vertical = 4.dp),
                style = MaterialTheme.typography.labelLarge
            )
        }

        // 关于
        SettingsSection(title = "关于") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "版本",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = appVersionName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(80.dp))
    }
}

private suspend fun buildTestMailBody(
    context: Context,
    useHighAccuracy: Boolean,
    amapWebApiKey: String
): String {
    val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    val body = StringBuilder()
        .appendLine("这是一封测试邮件，如果您收到说明配置正确。")
        .appendLine()
        .appendLine("发送时间: ${timeFormat.format(Date())}")

    val hasPermission = ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    if (!hasPermission) {
        return body.appendLine("当前坐标: 未授予定位权限").toString()
    }

    val gmsAvailable = try {
        GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    } catch (e: Exception) {
        false
    }

    if (!gmsAvailable) {
        return body.appendLine("当前坐标: Google Play Services 不可用，无法获取定位").toString()
    }

    return try {
        val fusedClient = LocationServices.getFusedLocationProviderClient(context)
        val priority = if (useHighAccuracy) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
        var location = withTimeoutOrNull(10_000L) {
            fusedClient.getCurrentLocation(priority, CancellationTokenSource().token).await()
        }
        if (location == null) {
            location = fusedClient.lastLocation.await()
        }

        if (location == null) {
            body.appendLine("当前坐标: 暂未获取到定位结果").toString()
        } else {
            val address = AmapReverseGeocoder.resolveAddress(
                context = context,
                apiKey = amapWebApiKey,
                latitude = location.latitude,
                longitude = location.longitude
            )
            val amapLatLng = AmapCoordinateConverter.toAmapLatLng(context, location.latitude, location.longitude)
            val amapLink = String.format(
                Locale.US,
                "https://uri.amap.com/marker?position=%.6f,%.6f&name=测试邮件定位",
                amapLatLng.longitude,
                amapLatLng.latitude
            )
            body
                .appendLine()
                .appendLine("当前最新坐标:")
                .apply {
                    if (address != null) appendLine("地址: $address")
                }
                .appendLine("经度: ${location.longitude}")
                .appendLine("纬度: ${location.latitude}")
                .appendLine("精度: ${location.accuracy}米")
                .appendLine("定位时间: ${timeFormat.format(Date(location.time))}")
                .appendLine("高德地图: $amapLink")
                .toString()
        }
    } catch (e: Exception) {
        body.appendLine("当前坐标: 获取失败（${e.message ?: "未知错误"}）").toString()
    }
}

@Composable
private fun SetupSummaryCard(
    isGuarding: Boolean,
    mailReady: Boolean,
    mapReady: Boolean
) {
    val readyCount = listOf(isGuarding, mailReady).count { it }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (readyCount == 2) "配置已就绪" else "完成基础配置",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "先保证邮件可达，再启动守护",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "$readyCount/2",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (readyCount == 2) Success else Warning,
                    fontWeight = FontWeight.Bold
                )
            }

            SetupSummaryItem(
                label = "邮件发送",
                isReady = mailReady,
                description = if (mailReady) "邮箱、授权码和接收人已填写" else "请填写邮箱并发送测试邮件"
            )
            SetupSummaryItem(
                label = "守护服务",
                isReady = isGuarding,
                description = if (isGuarding) "后台守护运行中" else "配置完成后启动守护"
            )
            SetupSummaryItem(
                label = "地图邮件",
                isReady = mapReady,
                description = if (mapReady) "静态地图 Key 已填写" else "可选，未配置时邮件不附带地图图片",
                optional = true
            )
        }
    }
}

@Composable
private fun SetupSummaryItem(
    label: String,
    isReady: Boolean,
    description: String,
    optional: Boolean = false
) {
    val statusColor = when {
        isReady -> Success
        optional -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> Warning
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = statusColor.copy(alpha = 0.1f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (isReady) "✓" else if (optional) "○" else "!",
            style = MaterialTheme.typography.labelLarge,
            color = statusColor,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(end = 10.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = if (isReady) "正常" else if (optional) "可选" else "待处理",
            style = MaterialTheme.typography.labelSmall,
            color = statusColor,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium
            )
        },
        placeholder = if (placeholder.isNotEmpty()) {
            { Text(text = placeholder, style = MaterialTheme.typography.bodyMedium) }
        } else null,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Accent,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        ),
        textStyle = MaterialTheme.typography.bodyMedium
    )
}
