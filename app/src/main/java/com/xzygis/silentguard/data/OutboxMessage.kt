package com.xzygis.silentguard.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "outbox", indices = [Index(value = ["state", "createdAt"])])
data class OutboxMessage(
    @PrimaryKey val id: String,
    val subject: String,
    val body: String,
    val recipient: String,
    val isHtml: Boolean = false,
    val automatic: Boolean = true,
    val state: String = "QUEUED",
    val attempts: Int = 0,
    val error: String = "",
    val workId: String = "",
    val leaseUntil: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface OutboxDao {
    @Insert suspend fun insert(message: OutboxMessage)
    @Query("SELECT * FROM outbox WHERE id = :id")
    suspend fun get(id: String): OutboxMessage?
    @Query("SELECT * FROM outbox WHERE state IN ('QUEUED','RETRYING','SENDING') ORDER BY createdAt LIMIT 200")
    suspend fun recoverable(): List<OutboxMessage>
    @Query("SELECT * FROM outbox ORDER BY createdAt DESC,id DESC LIMIT :limit OFFSET :offset")
    fun observe(limit: Int = 100, offset: Int = 0): Flow<List<OutboxMessage>>
    @Query("UPDATE outbox SET state='SENDING', attempts=attempts+1, leaseUntil=:lease, workId=:workId WHERE id=:id AND attempts<4 AND (state IN ('QUEUED','RETRYING') OR (state='SENDING' AND leaseUntil<:now))")
    suspend fun claim(id: String, workId: String, now: Long, lease: Long): Int
    @Query("UPDATE outbox SET state=:state, error=:error, leaseUntil=0 WHERE id=:id AND state='SENDING' AND workId=:workId AND attempts=:attempt")
    suspend fun finish(id: String, workId: String, attempt: Int, state: String, error: String = ""): Int
    @Query("UPDATE outbox SET state='QUEUED', attempts=attempts-1, leaseUntil=0 WHERE id=:id AND state='SENDING' AND workId=:workId AND attempts=:attempt")
    suspend fun releaseUnsent(id: String, workId: String, attempt: Int): Int
    @Query("UPDATE outbox SET state='FAILED', error='多次投递结果不确定，请核对收件箱后手动重试', leaseUntil=0 WHERE id=:id AND attempts>=4 AND (state IN ('QUEUED','RETRYING') OR (state='SENDING' AND leaseUntil<:now))")
    suspend fun failExhausted(id: String, now: Long): Int
    @Query("UPDATE outbox SET state='QUEUED', attempts=0, error='', leaseUntil=0 WHERE id=:id AND state='FAILED'")
    suspend fun retryFailed(id: String): Int
    @Query("DELETE FROM outbox WHERE state='SENT' AND createdAt<:before")
    suspend fun deleteDeliveredBefore(before: Long)
}
