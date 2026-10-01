package com.xzygis.silentguard.mail

import android.content.Context
import android.os.Build
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
import com.xzygis.silentguard.data.MonitorEvent
import com.xzygis.silentguard.location.AmapCoordinateConverter
import com.xzygis.silentguard.location.AmapReverseGeocoder
import java.text.SimpleDateFormat
import java.util.Calendar
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
                intervalMinutes.coerceAtLeast(15), TimeUnit.MINUTES
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

        suspend fun sendLocationReport(
            context: Context,
            events: List<MonitorEvent>,
            periodLabel: String = "待发送",
            bindEvents: Boolean = true,
            stableId: String? = null
        ): Boolean {
            val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val appConfig = AppConfig(context)
            val config = appConfig.getConfig()
            val mapUrl = StaticMapUrlBuilder.buildUrl(context, events, config.amapWebApiKey)
            val addressByEventId = events.associate { event ->
                event.id to AmapReverseGeocoder.extractAddress(event.detail)
            }

            val subject = "[${Build.MANUFACTURER} ${Build.MODEL}] ${events.size}条位置记录（$periodLabel）"

            return if (mapUrl != null) {
                // HTML 邮件：内嵌地图 + 位置详情
                val htmlBody = buildString {
                    appendLine("<!DOCTYPE html><html><body style=\"font-family:sans-serif;color:#333;\">")
                    appendLine("<h3 style=\"color:#1a73e8;\">📍 位置轨迹报告</h3>")
                    appendLine("<p>共 ${events.size} 个位置点（$periodLabel）</p>")
                    appendLine("<div style=\"margin:16px 0;\">")
                    appendLine("<img src=\"$mapUrl\" style=\"max-width:100%;border-radius:8px;border:1px solid #ddd;\" alt=\"轨迹地图\" />")
                    appendLine("</div>")
                    appendLine("<table style=\"border-collapse:collapse;width:100%;font-size:13px;\">")
                    appendLine("<tr style=\"background:#f5f5f5;\"><th style=\"padding:8px;text-align:left;\">#</th><th style=\"padding:8px;text-align:left;\">时间</th><th style=\"padding:8px;text-align:left;\">地址</th><th style=\"padding:8px;text-align:left;\">坐标</th><th style=\"padding:8px;text-align:left;\">精度</th></tr>")
                    events.forEachIndexed { index, event ->
                        val bgColor = if (index % 2 == 0) "#fff" else "#f9f9f9"
                        val time = timeFormat.format(Date(event.timestamp))
                        val amapLatLng = if (event.latitude != null && event.longitude != null) {
                            AmapCoordinateConverter.toAmapLatLng(context, event.latitude, event.longitude)
                        } else {
                            null
                        }
                        val coord = amapLatLng?.let { "%.4f, %.4f".format(Locale.US, it.latitude, it.longitude) } ?: "-"
                        val address = addressByEventId[event.id]?.let { escapeHtml(it) } ?: "-"
                        val accuracy = event.accuracy?.let { "%.0f米".format(it) } ?: "-"
                        val amapLink = amapLatLng?.let {
                            String.format(
                                Locale.US,
                                "https://uri.amap.com/marker?position=%.6f,%.6f&name=位置%d",
                                it.longitude,
                                it.latitude,
                                index + 1
                            )
                        } ?: "#"
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
                MailWorker.enqueue(context, subject, htmlBody, isHtml = true,
                    events = if (bindEvents) events else emptyList(), stableId = stableId) != null
            } else {
                // 降级：纯文本邮件（未配置高德 Key）
                val body = buildString {
                    if (events.isEmpty()) {
                        appendLine("共 0 个位置点（$periodLabel）")
                        appendLine()
                    }
                    events.forEach { event ->
                        val address = addressByEventId[event.id]
                        appendLine("--- ${timeFormat.format(Date(event.timestamp))} ---")
                        if (address != null && AmapReverseGeocoder.extractAddress(event.detail) == null) {
                            appendLine("地址: $address")
                        }
                        appendLine(event.detail)
                        appendLine()
                    }
                }
                MailWorker.enqueue(context, subject, body,
                    events = if (bindEvents) events else emptyList(), stableId = stableId) != null
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

    override suspend fun doWork(): Result {
        return try {
            if (!AppConfig(applicationContext).getConfig().isGuardingEnabled) return Result.success()
            MailWorker.recover(applicationContext)
            val dao = AppDatabase.getInstance(applicationContext).monitorEventDao()
            val pendingEvents = dao.getPendingLocationEvents()
            if (pendingEvents.isEmpty()) return Result.success()
            val mailSent = sendLocationReport(applicationContext, pendingEvents)

            if (!mailSent) {
                Log.w(TAG, "位置邮件发送失败，保留${pendingEvents.size}条记录为待发送")
                return Result.retry()
            }

            Log.d(TAG, "位置批次已持久化")
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "邮件调度失败: ${e.javaClass.simpleName}")
            Result.retry()
        }
    }

}
