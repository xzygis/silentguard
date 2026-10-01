package com.xzygis.silentguard.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class EventType {
    SMS, LOCATION
}

enum class EventStatus {
    PENDING, SENT, FAILED
}

@Entity(tableName = "monitor_events", indices = [
    Index(value = ["sourceKey"], unique = true),
    Index(value = ["type", "status", "timestamp"]),
    Index(value = ["timestamp", "id"]),
    Index(value = ["outboxId"])
])
data class MonitorEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val type: EventType,
    val title: String,
    val summary: String,
    val detail: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Float? = null,
    val status: EventStatus = EventStatus.PENDING,
    val timestamp: Long = System.currentTimeMillis(),
    val sourceKey: String? = null,
    val outboxId: String? = null
)
