package com.xzygis.silentguard.diagnostics

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.xzygis.silentguard.config.MonitorConfig
import com.xzygis.silentguard.data.MailSendRecord
import com.xzygis.silentguard.data.MonitorEvent
import com.xzygis.silentguard.service.SmsNotificationListenerService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppDiagnostics {

    fun hasSmsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasNotificationReadAccess(context: Context): Boolean {
        val componentName = ComponentName(
            context,
            SmsNotificationListenerService::class.java
        ).flattenToString()
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )
        return enabledListeners
            ?.split(":")
            ?.any { it.equals(componentName, ignoreCase = true) } == true
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    fun appDetailsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    fun notificationAccessIntent(): Intent {
        return Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }

    fun batteryOptimizationIntent(): Intent {
        return Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }

    fun buildReport(
        context: Context,
        config: MonitorConfig,
        latestSms: MonitorEvent?,
        latestLocation: MonitorEvent?,
        latestMail: MailSendRecord?,
        pendingCount: Int,
        failedMailCount: Int
    ): String {
        val versionName = context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName ?: "未知"
        val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        fun formatEvent(event: MonitorEvent?): String {
            return event?.let {
                "${it.title} / ${timeFormat.format(Date(it.timestamp))} / ${it.status}"
            } ?: "暂无"
        }

        fun formatMail(record: MailSendRecord?): String {
            return record?.let {
                "${it.subject} / ${timeFormat.format(Date(it.timestamp))} / ${it.status} / 重试${it.retryCount}次"
            } ?: "暂无"
        }

        return buildString {
            appendLine("SilentGuard 诊断报告")
            appendLine("生成时间: ${timeFormat.format(Date())}")
            appendLine("App 版本: $versionName")
            appendLine()
            appendLine("权限状态:")
            appendLine("- 短信权限: ${if (hasSmsPermission(context)) "已开启" else "未开启"}")
            appendLine("- 通知读取: ${if (hasNotificationReadAccess(context)) "已开启" else "未开启"}")
            appendLine("- 定位权限: ${if (hasLocationPermission(context)) "已开启" else "未开启"}")
            appendLine("- 电池优化: ${if (isIgnoringBatteryOptimizations(context)) "已忽略优化" else "可能受系统限制"}")
            appendLine()
            appendLine("配置状态:")
            appendLine("- 守护服务: ${if (config.isGuardingEnabled) "已启动" else "已停止"}")
            appendLine("- 邮件配置: ${if (config.senderEmail.isNotBlank() && config.recipientEmail.isNotBlank()) "已填写" else "未完整"}")
            appendLine("- 高德 Web Key: ${if (config.amapWebApiKey.isNotBlank()) "已填写" else "未填写"}")
            appendLine("- 位置间隔: ${config.locationIntervalMinutes} 分钟")
            appendLine("- 邮件间隔: ${config.emailIntervalMinutes} 分钟")
            appendLine()
            appendLine("最近状态:")
            appendLine("- 最近短信: ${formatEvent(latestSms)}")
            appendLine("- 最近位置: ${formatEvent(latestLocation)}")
            appendLine("- 最近邮件: ${formatMail(latestMail)}")
            appendLine("- 待发送记录: ${pendingCount}")
            appendLine("- 异常邮件记录: ${failedMailCount}")
            appendLine()
            appendLine("隐私说明: 本应用不上传到第三方服务器，记录仅保存在本机，并发送到用户自己配置的邮箱。")
        }
    }
}
