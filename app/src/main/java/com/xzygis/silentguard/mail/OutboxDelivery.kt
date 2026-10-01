package com.xzygis.silentguard.mail

import androidx.room.withTransaction
import com.xzygis.silentguard.data.*
import kotlinx.coroutines.CancellationException

/** Database-backed delivery; SMTP acceptance and local commit cannot form one atomic transaction. */
class OutboxDelivery(
    private val db: AppDatabase,
    private val isGuardingEnabled: suspend () -> Boolean,
    private val send: suspend (OutboxMessage) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis
) {
    enum class Outcome { DONE, RETRY }

    suspend fun deliver(taskId: String, workId: String): Outcome {
        var message = db.outboxDao().get(taskId) ?: return Outcome.DONE
        if (message.state == "SENT" || message.state == "FAILED") return Outcome.DONE
        if (message.automatic && !isGuardingEnabled()) return Outcome.RETRY
        val time = now()
        if (db.outboxDao().failExhausted(taskId, time) > 0) return Outcome.DONE
        if (db.outboxDao().claim(taskId, workId, time, time + 300_000) == 0) return Outcome.RETRY
        message = db.outboxDao().get(taskId) ?: return Outcome.DONE
        message = message.copy(subject = MailSubject.withDevicePrefix(message.subject))
        try {
            if (message.automatic && !isGuardingEnabled()) {
                db.outboxDao().releaseUnsent(taskId, workId, message.attempts)
                return Outcome.RETRY
            }
            val success = send(message)
            val state = if (success) "SENT" else if (message.attempts >= 4) "FAILED" else "RETRYING"
            db.withTransaction {
                if (db.outboxDao().finish(taskId, workId, message.attempts, state,
                        if (success) "" else "SMTP 投递失败，请检查配置和网络") == 0) return@withTransaction
                if (success) db.monitorEventDao().markDelivered(taskId)
                db.mailSendRecordDao().insert(MailSendRecord(
                    subject = message.subject, recipient = message.recipient,
                    status = MailSendStatus.valueOf(state), retryCount = (message.attempts - 1).coerceAtLeast(0),
                    errorMessage = if (success) "" else "SMTP 投递失败"
                ))
            }
            return if (state == "RETRYING") Outcome.RETRY else Outcome.DONE
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep the lease after unknown delivery/commit outcomes.
            return Outcome.RETRY
        }
    }
}
