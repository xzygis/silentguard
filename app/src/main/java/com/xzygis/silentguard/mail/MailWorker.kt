package com.xzygis.silentguard.mail

import android.content.Context
import androidx.room.withTransaction
import androidx.work.*
import com.xzygis.silentguard.config.AppConfig
import com.xzygis.silentguard.data.*
import kotlinx.coroutines.CancellationException
import java.util.UUID
import java.util.concurrent.TimeUnit

class MailWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        suspend fun enqueue(
            context: Context, subject: String, body: String, isHtml: Boolean = false,
            automatic: Boolean = true, events: List<MonitorEvent> = emptyList(),
            newEvent: MonitorEvent? = null, stableId: String? = null
        ): String? {
            val config = AppConfig(context).getConfig()
            if (automatic && !config.isGuardingEnabled) return null
            val db = AppDatabase.getInstance(context)
            val id = stableId ?: UUID.randomUUID().toString()
            val created = db.withTransaction {
                if (db.outboxDao().get(id) != null) return@withTransaction false
                if (newEvent != null) {
                    if (db.monitorEventDao().insert(newEvent.copy(outboxId = id)) == -1L) {
                        return@withTransaction false
                    }
                }
                if (events.isNotEmpty()) {
                    check(db.monitorEventDao().bindOutbox(events.map { it.id }, id) == events.size) {
                        "Report batch already claimed"
                    }
                }
                db.outboxDao().insert(OutboxMessage(
                    id, MailSubject.withDevicePrefix(subject), body,
                    config.recipientEmail, isHtml, automatic
                ))
                true
            }
            if (created) schedule(context, id)
            return if (created) id else null
        }

        fun schedule(context: Context, id: String, recovery: Boolean = false) {
            val work = OneTimeWorkRequestBuilder<MailWorker>()
                .setInputData(workDataOf("outbox_id" to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            // Recovery gets its own unique slot to bypass a paused worker's old backoff.
            // The database lease remains the authority for concurrent delivery.
            val name = if (recovery) "mail-recovery:$id" else "mail:$id"
            WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, work)
        }

        suspend fun recover(context: Context) {
            val enabled = AppConfig(context).getConfig().isGuardingEnabled
            AppDatabase.getInstance(context).outboxDao().recoverable().forEach {
                if ((!it.automatic || enabled) && it.leaseUntil < System.currentTimeMillis()) {
                    schedule(context, it.id, recovery = true)
                }
            }
        }

        suspend fun retry(context: Context, id: String) {
            if (AppDatabase.getInstance(context).outboxDao().retryFailed(id) > 0) schedule(context, id, recovery = true)
        }
    }

    override suspend fun doWork(): Result {
        val db = AppDatabase.getInstance(applicationContext)
        var taskId = inputData.getString("outbox_id")
        // Upgrade compatibility: persist the old WorkManager payload before delivery.
        if (taskId == null) {
            taskId = id.toString()
            if (db.outboxDao().get(taskId) == null) {
                val subject = MailSubject.withDevicePrefix(
                    inputData.getString("subject") ?: return Result.failure()
                )
                val body = inputData.getString("body") ?: return Result.failure()
                val config = AppConfig(applicationContext).getConfig()
                db.outboxDao().insert(OutboxMessage(taskId, subject, body, config.recipientEmail,
                    inputData.getBoolean("is_html", false)))
            }
        }
        val stored = db.outboxDao().get(taskId)
        if (stored?.automatic == true && !AppConfig(applicationContext).getConfig().isGuardingEnabled) {
            // Keep the durable task; reopening guard will schedule it again.
            return Result.success()
        }
        val delivery = OutboxDelivery(db,
            isGuardingEnabled = { AppConfig(applicationContext).getConfig().isGuardingEnabled },
            send = { message -> MailSender(applicationContext).sendMail(
                message.subject, message.body, message.isHtml, message.attempts,
                recipientOverride = message.recipient, messageId = taskId, recordResult = false
            ) })
        return when (delivery.deliver(taskId, id.toString())) {
            OutboxDelivery.Outcome.DONE -> Result.success()
            OutboxDelivery.Outcome.RETRY -> Result.retry()
        }
    }
}
