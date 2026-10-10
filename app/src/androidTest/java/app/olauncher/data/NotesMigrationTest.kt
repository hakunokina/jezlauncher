package app.olauncher.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Builds an old database by hand (schema from schemas/.../N.json) and lets Room open it with the migrations.
 * Not MigrationTestHelper: room-testing needs a newer kotlinx-serialization than the app ships, and AGP pins the
 * test classpath to the app's. Room still validates the migrated table against the entity when it opens the file.
 */
@RunWith(AndroidJUnit4::class)
class NotesMigrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    @After
    fun clean() {
        context.deleteDatabase(DB)
    }

    @Test
    fun migrate1To2KeepsNotesAndNumbersThemByCreation() {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(DB), null).use { db ->
            db.execSQL(V1_CREATE)
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '$V1_HASH')")
            // Inserted out of creation order, with a creation tie, to check the numbering
            db.execSQL("INSERT INTO notes (id, text, createdAt, strikedAt) VALUES (1, 'b', 200, NULL)")
            db.execSQL("INSERT INTO notes (id, text, createdAt, strikedAt) VALUES (2, 'a', 100, NULL)")
            db.execSQL("INSERT INTO notes (id, text, createdAt, strikedAt) VALUES (3, 'struck', 150, 999)")
            db.execSQL("INSERT INTO notes (id, text, createdAt, strikedAt) VALUES (4, 'tie', 200, NULL)")
            db.version = 1
        }

        val room = Room.databaseBuilder(context, NotesDatabase::class.java, DB)
            .addMigrations(NotesDatabase.MIGRATION_1_2, NotesDatabase.MIGRATION_2_3, NotesDatabase.MIGRATION_3_4)
            .build()
        try {
            val dao = room.noteDao()
            // Opening runs the migrations and Room's schema validation; a mismatch throws here
            val rows = runBlocking { dao.observeAll().first() }.sortedBy { it.id }
            assertEquals(
                listOf(
                    Note(1, "b", 200, null, position = 2),
                    Note(2, "a", 100, null, position = 0),
                    Note(3, "struck", 150, 999, position = 1),
                    Note(4, "tie", 200, null, position = 3),
                ),
                rows
            )
            assertEquals(4, room.openHelper.readableDatabase.version)
            // Same order the app showed before the migration: struck on top, then by creation
            assertEquals(listOf(3L, 2L, 1L, 4L), orderNotes(rows).map { it.id })

            runBlocking { dao.insertLast("new", 300) }
            val added = orderNotes(runBlocking { dao.observeAll().first() }).last()
            assertEquals("new", added.text)
            assertEquals(4L, added.position)
        } finally {
            room.close()
        }
    }

    @Test
    fun migrate2To3KeepsNotesUnscheduled() {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(DB), null).use { db ->
            db.execSQL(V2_CREATE)
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '$V2_HASH')")
            db.execSQL("INSERT INTO notes (id, text, createdAt, strikedAt, position) VALUES (1, 'a', 100, NULL, 1)")
            db.execSQL("INSERT INTO notes (id, text, createdAt, strikedAt, position) VALUES (2, 'struck', 200, 900, 0)")
            db.version = 2
        }

        val room = Room.databaseBuilder(context, NotesDatabase::class.java, DB)
            .addMigrations(NotesDatabase.MIGRATION_1_2, NotesDatabase.MIGRATION_2_3, NotesDatabase.MIGRATION_3_4)
            .build()
        try {
            val dao = room.noteDao()
            val rows = runBlocking { dao.all() }.sortedBy { it.id }
            assertEquals(listOf(Note(1, "a", 100, null, position = 1), Note(2, "struck", 200, 900, position = 0)), rows)
            rows.forEach { assertNull(it.schedule) }
            assertEquals(4, room.openHelper.readableDatabase.version)

            // The new columns round-trip a schedule, and clearing it leaves the rest of the row alone
            val weekly = NoteSchedule(ScheduleType.WEEKLY, minuteOfDay = 18 * 60, days = 0b101, alert = AlertType.ALARM, leadMinutes = 15)
            runBlocking { dao.setSchedule(1, weekly) }
            assertEquals(weekly, runBlocking { dao.byId(1) }?.schedule)
            val once = NoteSchedule(ScheduleType.ONCE, at = 1_800_000_000_000)
            runBlocking { dao.setSchedule(2, once) }
            assertEquals(once, runBlocking { dao.byId(2) }?.schedule)
            runBlocking { dao.setSchedule(1, null) }
            // It stays in the routine section, pinned
            assertEquals(Note(1, "a", 100, null, position = 1, pinned = true), runBlocking { dao.byId(1) })
        } finally {
            room.close()
        }
    }

    @Test
    fun migrate3To4KeepsNotesUnpinnedAndRoutinesInTheSection() {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(DB), null).use { db ->
            db.execSQL(V3_CREATE)
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '$V3_HASH')")
            db.execSQL("INSERT INTO notes (id, text, createdAt, strikedAt, position) VALUES (1, 'plain', 100, NULL, 0)")
            db.execSQL(
                "INSERT INTO notes (id, text, createdAt, strikedAt, position, schedule_type, schedule_minuteOfDay, " +
                        "schedule_alert, schedule_leadMinutes) VALUES (2, 'daily', 200, 900, 1, 'DAILY', 540, 'ALARM', 15)"
            )
            db.version = 3
        }

        val room = Room.databaseBuilder(context, NotesDatabase::class.java, DB)
            .addMigrations(NotesDatabase.MIGRATION_1_2, NotesDatabase.MIGRATION_2_3, NotesDatabase.MIGRATION_3_4)
            .build()
        try {
            val dao = room.noteDao()
            val rows = runBlocking { dao.all() }.sortedBy { it.id }
            val daily = NoteSchedule(ScheduleType.DAILY, minuteOfDay = 540, alert = AlertType.ALARM, leadMinutes = 15)
            assertEquals(
                listOf(Note(1, "plain", 100, null, position = 0), Note(2, "daily", 200, 900, position = 1, schedule = daily)),
                rows
            )
            assertEquals(4, room.openHelper.readableDatabase.version)
            // Nothing is pinned, the routine is in the section by its schedule
            assertEquals(listOf(false, true), rows.map { it.inSection })

            // The new column round-trips with the order
            runBlocking { dao.setPlaces(listOf(rows[0].copy(position = 5, pinned = true))) }
            assertEquals(Note(1, "plain", 100, null, position = 5, pinned = true), runBlocking { dao.byId(1) })
            // A note whose schedule goes stays in the section; the rest of the row is left alone
            runBlocking { dao.setSchedule(2, null) }
            assertEquals(Note(2, "daily", 200, 900, position = 1, pinned = true), runBlocking { dao.byId(2) })
            // Scheduling or removing a schedule on a note outside the section does not pin it
            runBlocking { dao.insertLast("new", 300) }
            val added = runBlocking { dao.all() }.single { it.text == "new" }
            runBlocking { dao.setSchedule(added.id, null) }
            assertEquals(false, runBlocking { dao.byId(added.id) }?.pinned)
            runBlocking { dao.setSchedule(added.id, daily) }
            assertEquals(false, runBlocking { dao.byId(added.id) }?.pinned)
        } finally {
            room.close()
        }
    }

    @Test
    fun storedAlertTypesReadBackAfterFullScreenWasAdded() {
        val room = Room.databaseBuilder(context, NotesDatabase::class.java, DB)
            .addMigrations(NotesDatabase.MIGRATION_1_2, NotesDatabase.MIGRATION_2_3, NotesDatabase.MIGRATION_3_4)
            .build()
        try {
            // Rows as the version 3 app before the full-screen type wrote them
            room.openHelper.writableDatabase.apply {
                execSQL(
                    "INSERT INTO notes (id, text, createdAt, position, schedule_type, schedule_minuteOfDay, schedule_alert, " +
                            "schedule_leadMinutes) VALUES (1, 'n', 100, 0, 'DAILY', 540, 'NOTIFICATION', 30)"
                )
                execSQL(
                    "INSERT INTO notes (id, text, createdAt, position, schedule_type, schedule_minuteOfDay, schedule_alert, " +
                            "schedule_leadMinutes) VALUES (2, 'a', 100, 1, 'DAILY', 540, 'ALARM', 30)"
                )
            }
            val dao = room.noteDao()
            assertEquals(AlertType.NOTIFICATION, runBlocking { dao.byId(1) }?.schedule?.alert)
            assertEquals(AlertType.ALARM, runBlocking { dao.byId(2) }?.schedule?.alert)

            val fullScreen = NoteSchedule(ScheduleType.DAILY, minuteOfDay = 540, alert = AlertType.FULL_SCREEN)
            runBlocking { dao.setSchedule(1, fullScreen) }
            assertEquals(fullScreen, runBlocking { dao.byId(1) }?.schedule)
            assertEquals(AlertType.ALARM, runBlocking { dao.byId(2) }?.schedule?.alert)
        } finally {
            room.close()
        }
    }

    private companion object {
        const val DB = "migration-test.db"
        const val V1_HASH = "9fffcf8e1c5a8305165160d7ec5f38e8"
        const val V1_CREATE = "CREATE TABLE IF NOT EXISTS `notes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`text` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `strikedAt` INTEGER)"
        const val V2_HASH = "8fae2ba900d7913e43a8eeb26ba57897"
        const val V2_CREATE = "CREATE TABLE IF NOT EXISTS `notes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`text` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `strikedAt` INTEGER, `position` INTEGER NOT NULL DEFAULT 0)"
        const val V3_HASH = "8dac1655aedd3b640d79fcc5dd24d9d4"
        const val V3_CREATE = "CREATE TABLE IF NOT EXISTS `notes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`text` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `strikedAt` INTEGER, `position` INTEGER NOT NULL DEFAULT 0, " +
                "`schedule_type` TEXT, `schedule_minuteOfDay` INTEGER, `schedule_days` INTEGER, `schedule_at` INTEGER, " +
                "`schedule_alert` TEXT, `schedule_leadMinutes` INTEGER)"
    }
}
