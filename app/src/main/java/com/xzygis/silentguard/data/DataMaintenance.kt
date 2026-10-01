package com.xzygis.silentguard.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import androidx.work.*
import com.xzygis.silentguard.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object DataExport {
    suspend fun write(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getInstance(context).monitorEventDao()
        val throughId = dao.maxId()
        var count = 0
        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { writer ->
            var after = 0L
            while (true) {
                val page = dao.exportPage(after, throughId = throughId)
                if (page.isEmpty()) break
                for (event in page) {
                    writer.appendLine(encode(event))
                    count++
                }
                after = page.last().id
            }
        }
        count
    }

    fun encode(event: MonitorEvent): String = JSONObject().apply {
        put("id", event.id)
        put("type", event.type.name)
        put("timestamp", event.timestamp)
        put("title", event.title)
        put("summary", event.summary)
        put("detail", event.detail)
        put("latitude", event.latitude ?: JSONObject.NULL)
        put("longitude", event.longitude ?: JSONObject.NULL)
        put("accuracy", event.accuracy ?: JSONObject.NULL)
        put("status", event.status.name)
    }.toString()
}

class RetentionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val config = AppConfig(applicationContext).getConfig()
        val before = System.currentTimeMillis() - config.retentionDays * 86_400_000L
        val db = AppDatabase.getInstance(applicationContext)
        db.withTransaction {
            db.monitorEventDao().deleteDeliveredBefore(before)
            db.outboxDao().deleteDeliveredBefore(before)
            db.mailSendRecordDao().deleteBefore(before)
        }
        com.xzygis.silentguard.mail.MailWorker.recover(applicationContext)
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("data_maintenance",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS).build())
        }
    }
}
