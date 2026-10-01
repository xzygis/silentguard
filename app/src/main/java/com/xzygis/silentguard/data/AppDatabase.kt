package com.xzygis.silentguard.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [MonitorEvent::class, MailSendRecord::class, OutboxMessage::class], version = 4, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {

    abstract fun monitorEventDao(): MonitorEventDao
    abstract fun mailSendRecordDao(): MailSendRecordDao
    abstract fun outboxDao(): OutboxDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `mail_send_records` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `subject` TEXT NOT NULL,
                        `recipient` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `errorMessage` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `mail_send_records` ADD COLUMN `retryCount` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE monitor_events ADD COLUMN sourceKey TEXT")
                db.execSQL("ALTER TABLE monitor_events ADD COLUMN outboxId TEXT")
                db.execSQL("CREATE UNIQUE INDEX index_monitor_events_sourceKey ON monitor_events(sourceKey)")
                db.execSQL("CREATE INDEX index_monitor_events_type_status_timestamp ON monitor_events(type,status,timestamp)")
                db.execSQL("CREATE INDEX index_monitor_events_timestamp_id ON monitor_events(timestamp,id)")
                db.execSQL("CREATE INDEX index_monitor_events_outboxId ON monitor_events(outboxId)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS outbox (
                    id TEXT NOT NULL PRIMARY KEY, subject TEXT NOT NULL, body TEXT NOT NULL,
                    recipient TEXT NOT NULL, isHtml INTEGER NOT NULL, automatic INTEGER NOT NULL,
                    state TEXT NOT NULL, attempts INTEGER NOT NULL, error TEXT NOT NULL,
                    workId TEXT NOT NULL, leaseUntil INTEGER NOT NULL, createdAt INTEGER NOT NULL)""")
                db.execSQL("CREATE INDEX index_outbox_state_createdAt ON outbox(state,createdAt)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE?.let { return@synchronized it }
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "silentguard.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .addMigrations(MIGRATION_2_3)
                    .addMigrations(MIGRATION_3_4)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
