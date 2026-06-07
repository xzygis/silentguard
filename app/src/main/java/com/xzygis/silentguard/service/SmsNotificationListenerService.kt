package com.xzygis.silentguard.service

import android.app.Notification
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.content.ContextCompat
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
 * 收到短信通知后，优先通过 ContentResolver 读取短信收件箱获取完整正文；
 * 若无 READ_SMS 权限则降级使用通知内容。
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

        // 用于去重，记录最近处理的短信ID
        @Volatile
        private var lastProcessedSmsId: Long = -1L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (!isAllowedSmsPackage(pkg)) return

        // 如果 RECEIVE_SMS 权限已授予，SmsReceiver 会直接处理短信，此处跳过避免重复
        if (ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.RECEIVE_SMS)
            == PackageManager.PERMISSION_GRANTED) {
            return
        }

        // 过滤掉 ongoing 类型的通知（如"正在运行"、前台服务通知）
        if (sbn.isOngoing) {
            Log.d(TAG, "跳过 ongoing 通知: $pkg")
            return
        }

        // 过滤掉没有 ticker/没有实际通知文本的系统通知
        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence("android.title")?.toString()
        val text = extras.getCharSequence("android.text")?.toString()

        // 必须有标题或正文才认为是短信通知
        if (title.isNullOrBlank() && text.isNullOrBlank()) return

        // 过滤掉分组摘要通知（如"3条新消息"）
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
            Log.d(TAG, "跳过分组摘要通知")
            return
        }

        Log.d(TAG, "检测到短信通知 - 包名: $pkg, 标题: $title, 内容预览: ${text?.take(20)}")

        scope.launch {
            try {
                // 优先通过 ContentResolver 读取最新短信完整内容
                val smsData = readLatestSmsFromInbox()

                val sender: String
                val smsBody: String
                val smsTime: String

                if (smsData != null) {
                    sender = smsData.address
                    smsBody = smsData.body
                    smsTime = smsData.time
                } else {
                    // 降级：使用通知内容
                    sender = title ?: "未知号码"
                    smsBody = text ?: ""
                    smsTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                }

                if (smsBody.isBlank()) {
                    Log.d(TAG, "短信正文为空，跳过")
                    return@launch
                }

                val dao = AppDatabase.getInstance(applicationContext).monitorEventDao()

                val subject = "[短信] 来自: $sender"
                val body = buildString {
                    appendLine("发送者: $sender")
                    appendLine("时间: $smsTime")
                    appendLine("内容:")
                    appendLine(smsBody)
                }

                val event = MonitorEvent(
                    type = EventType.SMS,
                    title = "来自 $sender",
                    summary = smsBody.take(100),
                    detail = body,
                    status = EventStatus.PENDING
                )

                val eventId = dao.insert(event)
                MailWorker.enqueue(applicationContext, subject, body)
                dao.updateStatus(eventId, EventStatus.SENT)
                Log.d(TAG, "短信已记录并入队发送: $sender")
            } catch (e: Exception) {
                Log.e(TAG, "处理短信通知失败: ${e.message}", e)
            }
        }
    }

    /**
     * 通过 ContentResolver 读取短信收件箱中最新一条短信。
     * 需要 READ_SMS 权限。
     */
    private fun readLatestSmsFromInbox(): SmsData? {
        if (ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "无 READ_SMS 权限，无法直接读取短信")
            return null
        }

        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("_id", "address", "body", "date"),
                null,
                null,
                "date DESC LIMIT 1"
            )
            if (cursor != null && cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow("_id"))
                // 去重：避免同一条短信被重复处理
                if (id == lastProcessedSmsId) {
                    Log.d(TAG, "短信ID重复($id)，跳过")
                    return null
                }
                lastProcessedSmsId = id

                val address = cursor.getString(cursor.getColumnIndexOrThrow("address")) ?: "未知号码"
                val body = cursor.getString(cursor.getColumnIndexOrThrow("body")) ?: ""
                val date = cursor.getLong(cursor.getColumnIndexOrThrow("date"))
                val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(date))
                return SmsData(address, body, timeStr)
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取短信收件箱失败: ${e.message}", e)
        } finally {
            cursor?.close()
        }
        return null
    }

    private data class SmsData(val address: String, val body: String, val time: String)

    private fun isAllowedSmsPackage(packageName: String): Boolean {
        val defaultSmsPackage = Telephony.Sms.getDefaultSmsPackage(applicationContext)
        return packageName == defaultSmsPackage || packageName in SMS_PACKAGES
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
