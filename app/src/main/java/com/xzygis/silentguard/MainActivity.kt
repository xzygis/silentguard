package com.xzygis.silentguard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.xzygis.silentguard.config.AppConfig
import com.xzygis.silentguard.config.MonitorConfig
import com.xzygis.silentguard.mail.MailSender
import com.xzygis.silentguard.service.MonitorForegroundService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var appConfig: AppConfig
    private lateinit var mailSender: MailSender

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filter { !it.value }.keys
        if (denied.isNotEmpty()) {
            Toast.makeText(this, "部分权限被拒绝，功能可能受限", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appConfig = AppConfig(this)
        mailSender = MailSender(this)

        requestPermissions()

        setContent {
            MaterialTheme {
                MonitorApp()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MonitorApp() {
        val config by appConfig.configFlow.collectAsState(initial = MonitorConfig())
        val scope = rememberCoroutineScope()

        var smtpHost by remember(config.smtpHost) { mutableStateOf(config.smtpHost) }
        var smtpPort by remember(config.smtpPort) { mutableStateOf(config.smtpPort.toString()) }
        var senderEmail by remember(config.senderEmail) { mutableStateOf(config.senderEmail) }
        var senderPassword by remember(config.senderPassword) { mutableStateOf(config.senderPassword) }
        var recipientEmail by remember(config.recipientEmail) { mutableStateOf(config.recipientEmail) }
        var locationInterval by remember(config.locationIntervalMinutes) { mutableStateOf(config.locationIntervalMinutes.toString()) }
        var isMonitoring by remember(config.isMonitoringEnabled) { mutableStateOf(config.isMonitoringEnabled) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("SilentGuard") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // SMTP 配置卡片
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("邮件配置", style = MaterialTheme.typography.titleMedium)

                        OutlinedTextField(
                            value = smtpHost,
                            onValueChange = { smtpHost = it },
                            label = { Text("SMTP 服务器") },
                            placeholder = { Text("smtp.feishu.cn") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = smtpPort,
                            onValueChange = { smtpPort = it },
                            label = { Text("SMTP 端口") },
                            placeholder = { Text("465") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )

                        OutlinedTextField(
                            value = senderEmail,
                            onValueChange = { senderEmail = it },
                            label = { Text("发送邮箱") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                        )

                        OutlinedTextField(
                            value = senderPassword,
                            onValueChange = { senderPassword = it },
                            label = { Text("邮箱授权码") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation()
                        )

                        OutlinedTextField(
                            value = recipientEmail,
                            onValueChange = { recipientEmail = it },
                            label = { Text("接收邮箱") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                        )
                    }
                }

                // 监控配置卡片
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("监控配置", style = MaterialTheme.typography.titleMedium)

                        OutlinedTextField(
                            value = locationInterval,
                            onValueChange = { locationInterval = it },
                            label = { Text("位置上报间隔 (分钟)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("启动监控", style = MaterialTheme.typography.bodyLarge)
                            Switch(
                                checked = isMonitoring,
                                onCheckedChange = { enabled ->
                                    isMonitoring = enabled
                                    toggleMonitoring(enabled)
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 操作按钮
                Button(
                    onClick = {
                        scope.launch {
                            val newConfig = MonitorConfig(
                                smtpHost = smtpHost.trim(),
                                smtpPort = smtpPort.trim().toIntOrNull() ?: 465,
                                senderEmail = senderEmail.trim(),
                                senderPassword = senderPassword.trim(),
                                recipientEmail = recipientEmail.trim(),
                                locationIntervalMinutes = locationInterval.trim().toIntOrNull() ?: 15,
                                isMonitoringEnabled = isMonitoring
                            )
                            appConfig.saveConfig(newConfig)
                            Toast.makeText(this@MainActivity, "配置已保存", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("保存配置")
                }

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            Toast.makeText(this@MainActivity, "正在发送测试邮件...", Toast.LENGTH_SHORT).show()
                            val success = mailSender.sendMail(
                                subject = "[测试] SilentGuard 测试邮件",
                                body = "这是一封测试邮件，如果您收到此邮件说明配置正确。"
                            )
                            if (success) {
                                Toast.makeText(this@MainActivity, "测试邮件发送成功", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this@MainActivity, "邮件发送失败，请检查配置和网络", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("发送测试邮件")
                }
            }
        }
    }

    private fun toggleMonitoring(enabled: Boolean) {
        kotlinx.coroutines.MainScope().launch {
            appConfig.setMonitoringEnabled(enabled)
        }

        val serviceIntent = Intent(this, MonitorForegroundService::class.java)
        if (enabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "监控已启动", Toast.LENGTH_SHORT).show()
        } else {
            stopService(serviceIntent)
            Toast.makeText(this, "监控已停止", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }
}
