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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.xzygis.silentguard.config.AppConfig
import com.xzygis.silentguard.config.MonitorConfig
import com.xzygis.silentguard.mail.MailSender
import com.xzygis.silentguard.ui.theme.*
import kotlinx.coroutines.launch

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(4.dp))

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

        // 操作
        Button(
            onClick = {
                scope.launch {
                    val newConfig = MonitorConfig(
                        smtpHost = smtpHost.trim(),
                        smtpPort = smtpPort.trim().toIntOrNull() ?: 465,
                        senderEmail = senderEmail.trim(),
                        senderPassword = senderPassword.trim(),
                        recipientEmail = recipientEmail.trim(),
                        locationIntervalMinutes = locationInterval.trim().toIntOrNull() ?: 5,
                        emailIntervalMinutes = emailInterval.trim().toIntOrNull() ?: 60,
                        isMonitoringEnabled = config.isMonitoringEnabled,
                        useHighAccuracy = useHighAccuracy
                    )
                    appConfig.saveConfig(newConfig)
                    Toast.makeText(context, "配置已保存", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Accent
            )
        ) {
            Text(
                text = "保存配置",
                modifier = Modifier.padding(vertical = 4.dp),
                style = MaterialTheme.typography.labelLarge
            )
        }

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

        Spacer(modifier = Modifier.height(32.dp))
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
