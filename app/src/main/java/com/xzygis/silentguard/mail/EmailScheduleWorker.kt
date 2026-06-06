package com.xzygis.silentguard.mail

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xzygis.silentguard.data.AppDatabase
import com.xzygis.silentguard.data.EventStatus
import com.xzygis.silentguard.config.AppConfig
import com.xzygis.silentguard.location.AmapReverseGeocoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class EmailScheduleWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "EmailScheduleWorker"
        private const val WORK_NAME = "email_schedule"

        fun schedule(context: Context, intervalMinutes: Long) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<EmailScheduleWorker>(
                intervalMinutes, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val dao = AppDatabase.getInstance(applicationContext).monitorEventDao()
            val pendingEvents = dao.getPendingLocationEvents()
            if (pendingEvents.isEmpty()) return Result.success()

            val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val appConfig = AppConfig(applicationContext)
            val config = appConfig.getConfig()
            val mapUrl = StaticMapUrlBuilder.buildUrl(pendingEvents, config.amapWebApiKey)
            val addressByEventId = pendingEvents.associate { event ->
                val existingAddress = AmapReverseGeocoder.extractAddress(event.detail)
                val resolvedAddress = if (existingAddress == null && event.latitude != null && event.longitude != null) {
                    AmapReverseGeocoder.resolveAddress(
                        context = applicationContext,
                        apiKey = config.amapWebApiKey,
                        latitude = event.latitude,
                        longitude = event.longitude
                    )
                } else {
                    existingAddress
                }
                event.id to resolvedAddress
            }

            val subject = "[位置] ${pendingEvents.size}条位置记录"

            val mailSent = if (mapUrl != null) {
                // HTML 邮件：内嵌地图 + 位置详情
                val htmlBody = buildString {
                    appendLine("<!DOCTYPE html><html><body style=\"font-family:sans-serif;color:#333;\">")
                    appendLine("<h3 style=\"color:#1a73e8;\">📍 位置轨迹报告</h3>")
                    appendLine("<p>共 ${pendingEvents.size} 个位置点</p>")
                    appendLine("<div style=\"margin:16px 0;\">")
                    appendLine("<img src=\"$mapUrl\" style=\"max-width:100%;border-radius:8px;border:1px solid #ddd;\" alt=\"轨迹地图\" />")
                    appendLine("</div>")
                    appendLine("<table style=\"border-collapse:collapse;width:100%;font-size:13px;\">")
                    appendLine("<tr style=\"background:#f5f5f5;\"><th style=\"padding:8px;text-align:left;\">#</th><th style=\"padding:8px;text-align:left;\">时间</th><th style=\"padding:8px;text-align:left;\">地址</th><th style=\"padding:8px;text-align:left;\">坐标</th><th style=\"padding:8px;text-align:left;\">精度</th></tr>")
                    pendingEvents.forEachIndexed { index, event ->
                        val bgColor = if (index % 2 == 0) "#fff" else "#f9f9f9"
                        val time = timeFormat.format(Date(event.timestamp))
                        val coord = "%.4f, %.4f".format(event.latitude, event.longitude)
                        val address = addressByEventId[event.id]?.let { escapeHtml(it) } ?: "-"
                        val accuracy = event.accuracy?.let { "%.0f米".format(it) } ?: "-"
                        val amapLink = "https://uri.amap.com/marker?position=${event.longitude},${event.latitude}&name=位置${index + 1}"
                        appendLine("<tr style=\"background:$bgColor;\">")
                        appendLine("<td style=\"padding:6px 8px;\">${index + 1}</td>")
                        appendLine("<td style=\"padding:6px 8px;\">$time</td>")
                        appendLine("<td style=\"padding:6px 8px;\">$address</td>")
                        appendLine("<td style=\"padding:6px 8px;\"><a href=\"$amapLink\" style=\"color:#1a73e8;\">$coord</a></td>")
                        appendLine("<td style=\"padding:6px 8px;\">$accuracy</td>")
                        appendLine("</tr>")
                    }
                    appendLine("</table>")
                    appendLine("<p style=\"margin-top:16px;font-size:12px;color:#999;\">由 SilentGuard 自动发送</p>")
                    appendLine("</body></html>")
                }
                MailSender(applicationContext).sendMail(subject, htmlBody, isHtml = true)
            } else {
                // 降级：纯文本邮件（未配置高德 Key）
                val body = buildString {
                    pendingEvents.forEach { event ->
                        val address = addressByEventId[event.id]
                        appendLine("--- ${timeFormat.format(Date(event.timestamp))} ---")
                        if (address != null && AmapReverseGeocoder.extractAddress(event.detail) == null) {
                            appendLine("地址: $address")
                        }
                        appendLine(event.detail)
                        appendLine()
                    }
                }
                MailSender(applicationContext).sendMail(subject, body)
            }

            if (!mailSent) {
                Log.w(TAG, "位置邮件发送失败，保留${pendingEvents.size}条记录为待发送")
                return Result.retry()
            }

            pendingEvents.forEach { event ->
                dao.updateStatus(event.id, EventStatus.SENT)
            }
            Log.d(TAG, "位置邮件发送成功，已标记${pendingEvents.size}条记录为已发送")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "邮件调度失败: ${e.message}", e)
            Result.retry()
        }
    }

    private fun escapeHtml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}
