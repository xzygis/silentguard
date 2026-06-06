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
            val subject = "[位置] ${pendingEvents.size}条位置记录"
            val body = buildString {
                pendingEvents.forEach { event ->
                    appendLine("--- ${timeFormat.format(Date(event.timestamp))} ---")
                    appendLine(event.detail)
                    appendLine()
                }
            }

            MailWorker.enqueue(applicationContext, subject, body)
            pendingEvents.forEach { event ->
                dao.updateStatus(event.id, EventStatus.SENT)
            }
            Log.d(TAG, "批量发送${pendingEvents.size}条位置邮件")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "邮件调度失败: ${e.message}", e)
            Result.retry()
        }
    }
}
