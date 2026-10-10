package app.olauncher.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.olauncher.sync.KioskSync

@Database(entities = [Note::class], version = 5)
abstract class NotesDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao

    companion object {
        /** Adds the manual order; existing notes are numbered 0..n-1 by creation time (id breaks ties). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE notes SET position = (SELECT COUNT(*) FROM notes AS o " +
                            "WHERE o.createdAt < notes.createdAt OR (o.createdAt = notes.createdAt AND o.id < notes.id))"
                )
            }
        }

        /** Adds the optional schedule (routine or one-off reminder); existing notes stay unscheduled (all NULL). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN schedule_type TEXT")
                db.execSQL("ALTER TABLE notes ADD COLUMN schedule_minuteOfDay INTEGER")
                db.execSQL("ALTER TABLE notes ADD COLUMN schedule_days INTEGER")
                db.execSQL("ALTER TABLE notes ADD COLUMN schedule_at INTEGER")
                db.execSQL("ALTER TABLE notes ADD COLUMN schedule_alert TEXT")
                db.execSQL("ALTER TABLE notes ADD COLUMN schedule_leadMinutes INTEGER")
            }
        }

        /** Adds the routine section's pin; existing notes are not pinned (scheduled ones are in the section anyway). */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Adds what the kiosk sync needs: every note gets a random UUID (version 4, as text), its last change is taken
         * as the later of creation and strike, nothing is deleted and nothing waits for the kiosk yet.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN syncId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE notes ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN deletedAt INTEGER")
                db.execSQL("ALTER TABLE notes ADD COLUMN dirty INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE notes SET updatedAt = MAX(createdAt, COALESCE(strikedAt, 0)), syncId = " +
                            "lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || " +
                            "substr(lower(hex(randomblob(2))), 2) || '-' || substr('89ab', 1 + (abs(random()) % 4), 1) || " +
                            "substr(lower(hex(randomblob(2))), 2) || '-' || lower(hex(randomblob(6)))"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_notes_syncId` ON `notes` (`syncId`)")
            }
        }

        @Volatile
        private var instance: NotesDatabase? = null

        fun get(context: Context): NotesDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, NotesDatabase::class.java, "notes.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build().also {
                    instance = it
                    // Every note write, from any screen or an alert, reaches the kiosk sync from here
                    KioskSync.watch(context.applicationContext, it)
                }
        }
    }
}
