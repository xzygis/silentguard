package com.xzygis.silentguard.mail

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.xzygis.silentguard.data.*
import kotlinx.coroutines.CancellationException
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
class OutboxDeliveryTest {
    private lateinit var db: AppDatabase
    private var time = 1_000L
    private val outbox get() = db.outboxDao()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
    }
    @After fun tearDown() { db.close() }

    private fun message(id: String = "task", automatic: Boolean = true) =
        OutboxMessage(id, "report", "位置\n".repeat(6000), "original@example.com", automatic = automatic)

    @Test fun `paused automatic delivery preserves attempts while explicit manual delivery is allowed`() = runTest {
        var sends = 0
        outbox.insert(message())
        outbox.insert(message("manual", automatic = false))
        val delivery = OutboxDelivery(db, { false }, { sends++; true }, { time })
        assertEquals(OutboxDelivery.Outcome.RETRY, delivery.deliver("task", "worker"))
        assertEquals(0, outbox.get("task")!!.attempts)
        assertEquals(OutboxDelivery.Outcome.DONE, delivery.deliver("manual", "manual-worker"))
        assertEquals(1, sends)
        assertEquals("QUEUED", outbox.get("task")!!.state)
        assertEquals("SENT", outbox.get("manual")!!.state)
    }

    @Test fun `successful delivery sends frozen payload once and updates only linked events`() = runTest {
        val stored = message()
        outbox.insert(stored)
        val linked = db.monitorEventDao().insert(MonitorEvent(type = EventType.LOCATION,
            title = "old", summary = "point", outboxId = stored.id))
        val unlinked = db.monitorEventDao().insert(MonitorEvent(type = EventType.LOCATION,
            title = "new", summary = "point"))
        val sent = mutableListOf<OutboxMessage>()
        val delivery = OutboxDelivery(db, { true }, { sent += it; true }, { time })
        assertEquals(OutboxDelivery.Outcome.DONE, delivery.deliver(stored.id, "worker"))
        delivery.deliver(stored.id, "duplicate")
        delivery.deliver("missing", "missing")
        assertEquals(1, sent.size)
        assertEquals(stored.id, sent.single().id)
        assertEquals(stored.body, sent.single().body)
        assertEquals(stored.recipient, sent.single().recipient)
        assertEquals(EventStatus.SENT, db.monitorEventDao().getEventById(linked)!!.status)
        assertEquals(EventStatus.PENDING, db.monitorEventDao().getEventById(unlinked)!!.status)
        assertEquals(MailSendStatus.SENT, db.mailSendRecordDao().getAllRecords().first().single().status)
    }

    @Test fun `four rejected sends stop automatically and manual retry retains the original payload`() = runTest {
        val stored = message()
        outbox.insert(stored)
        val event = db.monitorEventDao().insert(MonitorEvent(type = EventType.SMS,
            title = "sms", summary = "text", outboxId = stored.id))
        var sends = 0
        val delivery = OutboxDelivery(db, { true }, { sends++; false }, { time })
        repeat(3) { assertEquals(OutboxDelivery.Outcome.RETRY, delivery.deliver(stored.id, "worker")) }
        assertEquals(OutboxDelivery.Outcome.DONE, delivery.deliver(stored.id, "worker"))
        delivery.deliver(stored.id, "duplicate")
        assertEquals(4, sends)
        assertEquals("FAILED", outbox.get(stored.id)!!.state)
        assertEquals(EventStatus.PENDING, db.monitorEventDao().getEventById(event)!!.status)
        assertEquals(1, outbox.retryFailed(stored.id))
        val reset = outbox.get(stored.id)!!
        assertEquals(0, reset.attempts)
        assertEquals(stored.body, reset.body)
        assertEquals(stored.recipient, reset.recipient)
        assertEquals(0, outbox.retryFailed(stored.id))
        OutboxDelivery(db, { true }, { true }, { time }).deliver(stored.id, "retry")
        assertEquals("SENT", outbox.get(stored.id)!!.state)
        assertEquals(EventStatus.SENT, db.monitorEventDao().getEventById(event)!!.status)
    }

    @Test fun `active lease blocks another worker and expired lease resumes after database reopen`() = runTest {
        db.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "outbox-recovery-test"
        context.deleteDatabase(name)
        db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        outbox.insert(message())
        assertEquals(1, outbox.claim("task", "crashed-worker", time, time + 300_000))
        db.close()
        db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        var sends = 0
        val delivery = OutboxDelivery(db, { true }, { sends++; true }, { time })
        assertEquals(OutboxDelivery.Outcome.RETRY, delivery.deliver("task", "other"))
        assertEquals(0, sends)
        time += 300_001
        assertEquals(OutboxDelivery.Outcome.DONE, delivery.deliver("task", "recovery"))
        assertEquals(1, sends)
        assertEquals(2, outbox.get("task")!!.attempts)
        assertEquals("recovery", outbox.get("task")!!.workId)
        db.close()
        context.deleteDatabase(name)
    }

    @Test fun `cancellation propagates and uncertain send keeps its lease`() = runTest {
        outbox.insert(message())
        val cancelled = OutboxDelivery(db, { true }, { throw CancellationException("stopped") }, { time })
        try {
            cancelled.deliver("task", "cancelled")
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals("SENDING", outbox.get("task")!!.state)
        assertTrue(outbox.get("task")!!.leaseUntil > time)
        time += 300_001
        val uncertain = OutboxDelivery(db, { true }, { throw IllegalStateException("unknown outcome") }, { time })
        assertEquals(OutboxDelivery.Outcome.RETRY, uncertain.deliver("task", "unknown"))
        assertEquals("SENDING", outbox.get("task")!!.state)
        assertTrue(db.mailSendRecordDao().getAllRecords().first().isEmpty())
    }

    @Test fun `guard disabled after claim prevents SMTP execution`() = runTest {
        outbox.insert(message())
        var checks = 0
        val delivery = OutboxDelivery(db, { ++checks == 1 }, { fail("Guard was stopped"); true }, { time })
        assertEquals(OutboxDelivery.Outcome.RETRY, delivery.deliver("task", "worker"))
        assertEquals("QUEUED", outbox.get("task")!!.state)
        assertEquals(0L, outbox.get("task")!!.leaseUntil)
        assertEquals(0, outbox.get("task")!!.attempts)
    }

    @Test fun `repeated uncertain outcomes stop after four attempts and preserve unsent records`() = runTest {
        outbox.insert(message())
        var sends = 0
        val delivery = OutboxDelivery(db, { true }, { sends++; throw IllegalStateException("unknown") }, { time })
        repeat(4) {
            assertEquals(OutboxDelivery.Outcome.RETRY, delivery.deliver("task", "worker"))
            time += 300_001
        }
        assertEquals(OutboxDelivery.Outcome.DONE, delivery.deliver("task", "recovery"))
        assertEquals(4, sends)
        assertEquals("FAILED", outbox.get("task")!!.state)
        assertTrue(outbox.get("task")!!.error.isNotBlank())
        assertEquals(1, outbox.retryFailed("task"))
    }

    @Test fun `expired worker cannot overwrite the new owners state or mark events sent`() = runTest {
        outbox.insert(message())
        val event = db.monitorEventDao().insert(MonitorEvent(type = EventType.LOCATION,
            title = "point", summary = "", outboxId = "task"))
        val oldWorker = OutboxDelivery(db, { true }, {
            time += 300_001
            assertEquals(1, outbox.claim("task", "new-owner", time, time + 300_000))
            true
        }, { time })
        oldWorker.deliver("task", "old-owner")
        assertEquals("SENDING", outbox.get("task")!!.state)
        assertEquals("new-owner", outbox.get("task")!!.workId)
        assertEquals(EventStatus.PENDING, db.monitorEventDao().getEventById(event)!!.status)
        assertTrue(db.mailSendRecordDao().getAllRecords().first().isEmpty())
    }
}
