package com.xzygis.silentguard.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class MailSendStatus {
    SENT, FAILED
}

@Entity(tableName = "mail_send_records")
data class MailSendRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val subject: String,
    val recipient: String,
    val status: MailSendStatus,
    val errorMessage: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
