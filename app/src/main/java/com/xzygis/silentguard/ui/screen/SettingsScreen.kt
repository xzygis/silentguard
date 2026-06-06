package com.xzygis.silentguard.ui.screen

import android.widget.Toast
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.xzygis.silentguard.config.AppConfig
import com.xzygis.silentguard.config.MonitorConfig
import com.xzygis.silentguard.mail.MailSender
import com.xzygis.silentguard.ui.theme.*
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
@Composable
fun SettingsScreen(
    appConfig: AppConfig,
    mailSender: MailSender,
    isMonitoring: Boolean = false,
    onToggleMonitoring: (Boolean) -> Unit = {}
) {
    val config by appConfig.configFlow.collectAsState(initial = MonitorConfig())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var smtpHost by remember(config.smtpHost) { mutableStateOf(config.smtpHost) }
    var smtpPort by remember(config.smtpPort) { mutableStateOf(config.smtpPort.toString()) }
    var senderEmail by remember(config.senderEmail) { mutableStateOf(config.senderEmail) }
    var senderPassword by remember(config.senderPassword) { mutableStateOf(config.senderPassword) }
    var recipientEmail by remember(config.recipientEmail) { mutableStateOf(config.recipientEmail) }
    var locationInterval by remember(config.locationIntervalMinutes) { mutableStateOf(config.locationIntervalMinutes.toString()) }
    var emailInterval by remember(config.emailIntervalMinutes) { mutableStateOf(config.emailIntervalMinutes.toString()) }
    var useHighAccuracy by remember(config.useHighAccuracy) { mutableStateOf(config.useHighAccuracy) }
    var amapWebApiKey by remember(config.amapWebApiKey) { mutableStateOf(config.amapWebApiKey) }

    // 自动保存：任意字段变化后 800ms 自动持久化
    LaunchedEffect(Unit) {
        snapshotFlow {
            MonitorConfig(
                smtpHost = smtpHost.trim(),
                smtpPort = smtpPort.trim().toIntOrNull() ?: 465,
                senderEmail = senderEmail.trim(),
                senderPassword = senderPassword.trim(),
                recipientEmail = recipientEmail.trim(),
                locationIntervalMinutes = locationInterval.trim().toIntOrNull() ?: 5,
                emailIntervalMinutes = emailInterval.trim().toIntOrNull() ?: 60,
                isMonitoringEnabled = config.isMonitoringEnabled,
                useHighAccuracy = useHighAccuracy,
                amapWebApiKey = amapWebApiKey.trim()
            )
        }
            .drop(1)
            .debounce(800)
            .collectLatest { newConfig ->
                appConfig.saveConfig(newConfig)
            }
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
            isMonitoring = isMonitoring,
            mailReady = senderEmail.isNotBlank() &&
                    senderPassword.isNotBlank() &&
                    recipientEmail.isNotBlank(),
            mapReady = amapWebApiKey.isNotBlank()
        )

        // 监控开关
        SettingsSection(title = "监控服务") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "启动监控",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isMonitoring) "短信和位置监控运行中" else "监控已关闭",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isMonitoring,
                    onCheckedChange = { onToggleMonitoring(it) },
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

        // 监控配置
        SettingsSection(title = "监控参数") {
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
                        body = "这是一封测试邮件，如果您收到说明配置正确。\n\n发送时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}"
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
                    text = "1.0.0",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(80.dp))
    }
}

@Composable
private fun SetupSummaryCard(
    isMonitoring: Boolean,
    mailReady: Boolean,
    mapReady: Boolean
) {
    val readyCount = listOf(isMonitoring, mailReady).count { it }
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
                label = "邮件上报",
                isReady = mailReady,
                description = if (mailReady) "邮箱、授权码和接收人已填写" else "请填写邮箱并发送测试邮件"
            )
            SetupSummaryItem(
                label = "守护服务",
                isReady = isMonitoring,
                description = if (isMonitoring) "后台守护运行中" else "配置完成后启动守护"
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
