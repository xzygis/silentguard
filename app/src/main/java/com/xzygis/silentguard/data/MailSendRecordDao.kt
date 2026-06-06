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
}
