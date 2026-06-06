package com.xzygis.silentguard.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class EventType {
    SMS, LOCATION
}

enum class EventStatus {
    PENDING, SENT, FAILED
}

@Entity(tableName = "monitor_events")
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
    val timestamp: Long = System.currentTimeMillis()
)
