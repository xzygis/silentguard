package com.xzygis.silentguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.xzygis.silentguard.data.AppDatabase
import com.xzygis.silentguard.data.EventStatus
import com.xzygis.silentguard.data.EventType
import com.xzygis.silentguard.data.MonitorEvent
import com.xzygis.silentguard.mail.MailWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val senderMap = mutableMapOf<String, StringBuilder>()

                for (sms in messages) {
                    val sender = sms.displayOriginatingAddress ?: "未知号码"
                    val body = sms.messageBody ?: ""
                    senderMap.getOrPut(sender) { StringBuilder() }.append(body)
                }

                val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val currentTime = timeFormat.format(Date())
                val dao = AppDatabase.getInstance(context).monitorEventDao()

                for ((sender, content) in senderMap) {
                    val subject = "[短信] 来自: $sender"
                    val body = buildString {
                        appendLine("发送者: $sender")
                        appendLine("时间: $currentTime")
                        appendLine("内容:")
                        appendLine(content.toString())
                    }

                    // 记录到数据库
                    val event = MonitorEvent(
                        type = EventType.SMS,
                        title = "来自 $sender",
                        summary = content.toString().take(100),
                        detail = body,
                        status = EventStatus.PENDING
                    )
                    try {
                        val eventId = dao.insert(event)
                        MailWorker.enqueue(context, subject, body)
                        dao.updateStatus(eventId, EventStatus.SENT)
                    } catch (e: Exception) {
                        Log.e(TAG, "记录短信事件失败: ${e.message}", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "处理短信失败: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
