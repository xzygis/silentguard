package com.xzygis.silentguard.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppDatabaseMigrationTest {
    @Test
    fun `all supported old databases preserve events and validate against current Room schema`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (version in 1..3) {
            val name = "migration-$version.db"
            context.deleteDatabase(name)
            val path = context.getDatabasePath(name)
            path.parentFile!!.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
                old.execSQL("""CREATE TABLE monitor_events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, type TEXT NOT NULL,
                    title TEXT NOT NULL, summary TEXT NOT NULL, detail TEXT NOT NULL,
                    latitude REAL, longitude REAL, accuracy REAL, status TEXT NOT NULL,
                    timestamp INTEGER NOT NULL)""")
                old.execSQL("""INSERT INTO monitor_events
                    (id,type,title,summary,detail,latitude,longitude,accuracy,status,timestamp)
                    VALUES (7,'LOCATION','old','old','preserved',1.0,2.0,3.0,'PENDING',100)""")
                if (version >= 2) {
                    old.execSQL("""CREATE TABLE mail_send_records (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, subject TEXT NOT NULL,
                        recipient TEXT NOT NULL, status TEXT NOT NULL, errorMessage TEXT NOT NULL,
                        timestamp INTEGER NOT NULL)""")
                    old.execSQL("INSERT INTO mail_send_records VALUES (1,'old','a@example.com','SENT','',100)")
                }
                if (version >= 3) old.execSQL("ALTER TABLE mail_send_records ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0")
                old.version = version
            }
            val upgraded = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
                .build()
            try {
                val event = upgraded.monitorEventDao().getEventById(7)
                assertEquals("preserved", event?.detail)
                assertEquals(EventStatus.PENDING, event?.status)
                assertNull(event?.sourceKey)
                assertEquals(4, upgraded.openHelper.readableDatabase.version)
                upgraded.outboxDao().insert(OutboxMessage("new", "subject", "body", "a@example.com"))
                assertEquals("QUEUED", upgraded.outboxDao().get("new")?.state)
                if (version >= 2) upgraded.openHelper.readableDatabase.query(
                    "SELECT retryCount FROM mail_send_records WHERE id=1"
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            } finally {
                upgraded.close()
                context.deleteDatabase(name)
            }
        }
    }
}
