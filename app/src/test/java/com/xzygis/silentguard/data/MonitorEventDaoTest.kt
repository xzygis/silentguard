package com.xzygis.silentguard.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MonitorEventDaoTest {
    private lateinit var db: AppDatabase
    private val dao get() = db.monitorEventDao()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
    }
    @After fun tearDown() { db.close() }

    private fun event(time: Long, key: String? = null, state: EventStatus = EventStatus.PENDING) =
        MonitorEvent(type = EventType.LOCATION, title = "location", summary = "point",
            timestamp = time, sourceKey = key, status = state)

    @Test
    fun `cross day batch marks only delivered members and leaves later points pending`() = runTest {
        val yesterday = dao.insert(event(1))
        val today = dao.insert(event(86_400_001))
        val batch = dao.getPendingLocationEvents()
        assertEquals(listOf(yesterday, today), batch.map { it.id })
        assertEquals(2, dao.bindOutbox(batch.map { it.id }, "batch"))
        val later = dao.insert(event(86_400_002))
        assertEquals(listOf(later), dao.getPendingLocationEvents().map { it.id })
        dao.markDelivered("batch")
        assertEquals(EventStatus.SENT, dao.getEventById(yesterday)?.status)
        assertEquals(EventStatus.SENT, dao.getEventById(today)?.status)
        assertEquals(EventStatus.PENDING, dao.getEventById(later)?.status)
    }

    @Test
    fun `duplicate sources are rejected and independent equal timestamps remain valid`() = runTest {
        val original = dao.insert(event(100, "source"))
        assertTrue(original > 0)
        assertEquals(-1L, dao.insert(event(101, "source")))
        assertTrue(dao.insert(event(100, "different")) > 0)
        assertEquals(2, dao.exportPage(0).size)
    }

    @Test
    fun `transaction rollback leaves event eligible for another batch`() = runTest {
        val id = dao.insert(event(100))
        try {
            db.withTransaction {
                dao.bindOutbox(listOf(id), "rolled-back")
                throw IllegalStateException("rollback")
            }
        } catch (_: IllegalStateException) { }
        assertNull(dao.getEventById(id)?.outboxId)
        assertEquals(1, dao.getPendingLocationEvents().size)
    }

    @Test
    fun `retention deletes only old delivered events`() = runTest {
        val sent = dao.insert(event(1, state = EventStatus.SENT))
        val pending = dao.insert(event(1))
        val failed = dao.insert(event(1, state = EventStatus.FAILED))
        val recent = dao.insert(event(100, state = EventStatus.SENT))
        dao.deleteDeliveredBefore(50)
        assertNull(dao.getEventById(sent))
        listOf(pending, failed, recent).forEach { assertNotNull(dao.getEventById(it)) }
    }

    @Test
    fun `pages have deterministic ties and export respects high water mark`() = runTest {
        val ids = (1..5).map { dao.insert(event(100)) }
        assertEquals(ids.reversed().take(2), dao.observePage(2).first().map { it.id })
        assertEquals(ids.reversed().drop(2).take(2), dao.observePage(2, 2).first().map { it.id })
        val highWater = dao.maxId()
        dao.insert(event(101))
        assertEquals(ids, dao.exportPage(0, throughId = highWater).map { it.id })
        assertTrue(dao.observePage(10, type = EventType.SMS).first().isEmpty())
    }

    @Test
    fun `pending batches are capped at one hundred`() = runTest {
        repeat(105) { dao.insert(event(it.toLong())) }
        assertEquals(100, dao.getPendingLocationEvents().size)
    }
}
