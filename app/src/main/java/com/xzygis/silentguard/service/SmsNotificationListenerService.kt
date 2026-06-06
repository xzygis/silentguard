package com.xzygis.silentguard.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.xzygis.silentguard.data.AppDatabase
import com.xzygis.silentguard.data.EventStatus
import com.xzygis.silentguard.data.EventType
import com.xzygis.silentguard.data.MonitorEvent
import com.xzygis.silentguard.mail.MailWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 通过读取通知栏内容记录短信，作为 RECEIVE_SMS 权限被拒时的兼容方案。
 * 用户只需在系统设置中开启"通知使用权"即可，无需 ADB。
 */
class SmsNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "SmsNotifListener"

        // 常见短信应用的包名
        private val SMS_PACKAGES = setOf(
            "com.android.mms",              // AOSP 短信
            "com.google.android.apps.messaging", // Google Messages
            "com.samsung.android.messaging", // 三星短信
            "com.miui.mms",                 // 小米短信
            "com.huawei.message",           // 华为短信
            "com.oppo.mms",                // OPPO 短信
            "com.vivo.mms",                // vivo 短信
            "com.oneplus.mms",             // 一加短信
            "com.coloros.mms",             // ColorOS 短信
            "com.zte.mms",                 // 中兴短信
            "com.meizu.mms",               // 魅族短信
        )
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg !in SMS_PACKAGES) return

        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence("android.title")?.toString() ?: "未知号码"
        val text = extras.getCharSequence("android.text")?.toString() ?: return

        // 忽略空内容
        if (text.isBlank()) return

        Log.d(TAG, "读取短信通知 - 来自: $title, 内容: ${text.take(20)}...")

        scope.launch {
            try {
                val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val currentTime = timeFormat.format(Date())
                val dao = AppDatabase.getInstance(applicationContext).monitorEventDao()

                val subject = "[短信] 来自: $title"
                val body = buildString {
                    appendLine("发送者: $title")
                    appendLine("时间: $currentTime")
                    appendLine("来源: 短信通知")
                    appendLine("内容:")
                    appendLine(text)
                }

                val event = MonitorEvent(
                    type = EventType.SMS,
                    title = "来自 $title",
                    summary = text.take(100),
                    detail = body,
                    status = EventStatus.PENDING
                )

                val eventId = dao.insert(event)
                MailWorker.enqueue(applicationContext, subject, body)
                dao.updateStatus(eventId, EventStatus.SENT)
            } catch (e: Exception) {
                Log.e(TAG, "处理短信通知失败: ${e.message}", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
