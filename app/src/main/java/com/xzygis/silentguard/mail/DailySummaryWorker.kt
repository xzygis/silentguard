package com.xzygis.silentguard.mail

import android.content.Context
import androidx.work.*
import com.xzygis.silentguard.config.AppConfig
import com.xzygis.silentguard.data.AppDatabase
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Finalize completed calendar days; persistent cursor makes process death resumable. */
class DailySummaryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!AppConfig(applicationContext).getConfig().isGuardingEnabled) return Result.success()
        val prefs = applicationContext.getSharedPreferences("daily_summary", Context.MODE_PRIVATE)
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -1) }
        var day = prefs.getLong("next_day", yesterday.timeInMillis)
        val db = AppDatabase.getInstance(applicationContext)
        repeat(7) {
            if (day >= today.timeInMillis) return Result.success()
            val end = Calendar.getInstance().apply {
                timeInMillis = day
                add(Calendar.DAY_OF_MONTH, 1)
            }.timeInMillis
            var afterId = 0L
            var part = 0
            while (true) {
                val batch = db.monitorEventDao().locationDayPage(day, end, afterId)
                if (batch.isEmpty() && part > 0) break
                val taskId = "summary:$day:$part"
                if (db.outboxDao().get(taskId) == null) {
                    val label = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(day)
                    val queued = EmailScheduleWorker.sendLocationReport(applicationContext, batch,
                        periodLabel = "$label 汇总 第${part + 1}批", bindEvents = false, stableId = taskId)
                    if (!queued && db.outboxDao().get(taskId) == null) return Result.retry()
                }
                if (batch.size < 100) break
                afterId = batch.last().id
                part++
            }
            day = end
            if (!prefs.edit().putLong("next_day", day).commit()) return Result.retry()
        }
        return Result.retry()
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("daily_summary",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<DailySummaryWorker>(15, TimeUnit.MINUTES).build())
        }
    }
}
