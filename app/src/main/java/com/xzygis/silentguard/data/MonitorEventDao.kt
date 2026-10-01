package com.xzygis.silentguard.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MonitorEventDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: MonitorEvent): Long

    @Update
    suspend fun update(event: MonitorEvent)

    @Query("SELECT * FROM monitor_events ORDER BY timestamp DESC")
    fun getAllEvents(): Flow<List<MonitorEvent>>

    @Query("SELECT * FROM monitor_events WHERE type = :type ORDER BY timestamp DESC")
    fun getEventsByType(type: EventType): Flow<List<MonitorEvent>>

    @Query("SELECT * FROM monitor_events WHERE type = :type ORDER BY timestamp DESC LIMIT 1")
    fun getLatestEventByType(type: EventType): Flow<MonitorEvent?>

    @Query("SELECT * FROM monitor_events WHERE type = 'LOCATION' AND latitude IS NOT NULL ORDER BY timestamp DESC")
    fun getLocationEvents(): Flow<List<MonitorEvent>>

    @Query("SELECT * FROM monitor_events WHERE type = 'LOCATION' AND latitude IS NOT NULL ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestLocationEvent(): MonitorEvent?

    @Query("SELECT * FROM monitor_events WHERE type = 'LOCATION' AND latitude IS NOT NULL AND timestamp BETWEEN :startTime AND :endTime ORDER BY timestamp ASC")
    fun getLocationEventsBetween(startTime: Long, endTime: Long): Flow<List<MonitorEvent>>

    @Query("SELECT COUNT(*) FROM monitor_events WHERE timestamp >= :since")
    fun getEventCountSince(since: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM monitor_events WHERE type = :type AND timestamp >= :since")
    fun getEventCountByTypeSince(type: EventType, since: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM monitor_events WHERE status = :status")
    fun getEventCountByStatus(status: EventStatus): Flow<Int>

    @Query("SELECT * FROM monitor_events ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentEvents(limit: Int): Flow<List<MonitorEvent>>

    @Query("UPDATE monitor_events SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: EventStatus)

    @Query("SELECT * FROM monitor_events WHERE id = :id")
    suspend fun getEventById(id: Long): MonitorEvent?

    @Query("SELECT * FROM monitor_events WHERE type = 'LOCATION' AND status = 'PENDING' AND outboxId IS NULL ORDER BY timestamp ASC, id ASC LIMIT 100")
    suspend fun getPendingLocationEvents(): List<MonitorEvent>

    @Query("UPDATE monitor_events SET outboxId=:outboxId WHERE id IN (:ids) AND outboxId IS NULL AND status='PENDING'")
    suspend fun bindOutbox(ids: List<Long>, outboxId: String): Int

    @Query("UPDATE monitor_events SET status='SENT' WHERE outboxId=:outboxId")
    suspend fun markDelivered(outboxId: String)

    @Query("SELECT * FROM monitor_events WHERE (:type IS NULL OR type=:type) ORDER BY timestamp DESC, id DESC LIMIT :limit OFFSET :offset")
    fun observePage(limit: Int, offset: Int = 0, type: EventType? = null): Flow<List<MonitorEvent>>

    @Query("SELECT * FROM monitor_events WHERE id>:afterId AND id<=:throughId ORDER BY id LIMIT :limit")
    suspend fun exportPage(afterId: Long, limit: Int = 200, throughId: Long = Long.MAX_VALUE): List<MonitorEvent>

    @Query("SELECT COALESCE(MAX(id),0) FROM monitor_events")
    suspend fun maxId(): Long

    @Query("SELECT * FROM monitor_events WHERE type='LOCATION' AND timestamp>=:start AND timestamp<:end AND id>:afterId ORDER BY id LIMIT 100")
    suspend fun locationDayPage(start: Long, end: Long, afterId: Long): List<MonitorEvent>

    @Query("DELETE FROM monitor_events WHERE status='SENT' AND timestamp<:before")
    suspend fun deleteDeliveredBefore(before: Long)

    @Query("SELECT * FROM monitor_events WHERE type = 'LOCATION' AND latitude IS NOT NULL AND timestamp >= :startOfDay ORDER BY timestamp ASC")
    suspend fun getTodayLocationEvents(startOfDay: Long): List<MonitorEvent>

    @Query("DELETE FROM monitor_events")
    suspend fun clearAll()
}
