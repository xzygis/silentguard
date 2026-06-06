package com.xzygis.silentguard.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MailSendRecordDao {

    @Insert
    suspend fun insert(record: MailSendRecord): Long

    @Query("SELECT * FROM mail_send_records ORDER BY timestamp DESC")
    fun getAllRecords(): Flow<List<MailSendRecord>>

    @Query("SELECT * FROM mail_send_records ORDER BY timestamp DESC LIMIT 1")
    fun getLatestRecord(): Flow<MailSendRecord?>

    @Query("SELECT COUNT(*) FROM mail_send_records WHERE status IN ('FAILED', 'RETRYING')")
    fun getUnhealthyCount(): Flow<Int>

    @Query("DELETE FROM mail_send_records")
    suspend fun clearAll()
}
