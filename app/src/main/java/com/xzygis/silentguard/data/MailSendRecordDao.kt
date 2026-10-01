package com.xzygis.silentguard.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MailSendRecordDao {

    @Insert
    suspend fun insert(record: MailSendRecord): Long

    @Query("SELECT * FROM mail_send_records ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun getAllRecords(limit: Int = 100): Flow<List<MailSendRecord>>

    @Query("SELECT * FROM mail_send_records ORDER BY timestamp DESC LIMIT 1")
    fun getLatestRecord(): Flow<MailSendRecord?>

    @Query("SELECT (SELECT COUNT(*) FROM outbox WHERE state IN ('FAILED','RETRYING')) + (SELECT COUNT(*) FROM (SELECT status FROM mail_send_records ORDER BY timestamp DESC,id DESC LIMIT 1) WHERE status='FAILED')")
    fun getUnhealthyCount(): Flow<Int>

    @Query("DELETE FROM mail_send_records WHERE timestamp<:before")
    suspend fun deleteBefore(before: Long)

    @Query("DELETE FROM mail_send_records")
    suspend fun clearAll()
}
