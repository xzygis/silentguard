package com.xzygis.silentguard.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MonitorEventDao {

    @Insert
    suspend fun insert(event: MonitorEvent): Long

    @Update
    suspend fun update(event: MonitorEvent)

    @Query("SELECT * FROM monitor_events ORDER BY timestamp DESC")
    fun getAllEvents(): Flow<List<MonitorEvent>>

    @Query("SELECT * FROM monitor_events WHERE type = :type ORDER BY timestamp DESC")
    fun getEventsByType(type: EventType): Flow<List<MonitorEvent>>

    @Query("SELECT * FROM monitor_events WHERE type = 'LOCATION' AND latitude IS NOT NULL ORDER BY timestamp DESC")
    fun getLocationEvents(): Flow<List<MonitorEvent>>

    @Query("SELECT * FROM monitor_events WHERE type = 'LOCATION' AND latitude IS NOT NULL AND timestamp BETWEEN :startTime AND :endTime ORDER BY timestamp ASC")
    fun getLocationEventsBetween(startTime: Long, endTime: Long): Flow<List<MonitorEvent>>

    @Query("SELECT COUNT(*) FROM monitor_events WHERE timestamp >= :since")
    fun getEventCountSince(since: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM monitor_events WHERE type = :type AND timestamp >= :since")
    fun getEventCountByTypeSince(type: EventType, since: Long): Flow<Int>

    @Query("SELECT * FROM monitor_events ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentEvents(limit: Int): Flow<List<MonitorEvent>>

    @Query("UPDATE monitor_events SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: EventStatus)

    @Query("SELECT * FROM monitor_events WHERE id = :id")
    suspend fun getEventById(id: Long): MonitorEvent?

    @Query("SELECT * FROM monitor_events WHERE type = 'LOCATION' AND status = 'PENDING' ORDER BY timestamp ASC")
    suspend fun getPendingLocationEvents(): List<MonitorEvent>
}
