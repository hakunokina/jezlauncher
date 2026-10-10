package app.olauncher.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.olauncher.sync.SyncItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
            .addMigrations(*MIGRATIONS)
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
                rows.local()
            )
            assertEquals(5, room.openHelper.readableDatabase.version)
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
            .addMigrations(*MIGRATIONS)
            .build()
        try {
            val dao = room.noteDao()
            val rows = runBlocking { dao.all() }.sortedBy { it.id }
            assertEquals(listOf(Note(1, "a", 100, null, position = 1), Note(2, "struck", 200, 900, position = 0)), rows.local())
            rows.forEach { assertNull(it.schedule) }
            assertEquals(5, room.openHelper.readableDatabase.version)

            // The new columns round-trip a schedule, and clearing it leaves the rest of the row alone
            val weekly = NoteSchedule(ScheduleType.WEEKLY, minuteOfDay = 18 * 60, days = 0b101, alert = AlertType.ALARM, leadMinutes = 15)
            runBlocking { dao.setSchedule(1, weekly, NOW) }
            assertEquals(weekly, runBlocking { dao.byId(1) }?.schedule)
            val once = NoteSchedule(ScheduleType.ONCE, at = 1_800_000_000_000)
            runBlocking { dao.setSchedule(2, once, NOW) }
            assertEquals(once, runBlocking { dao.byId(2) }?.schedule)
            runBlocking { dao.setSchedule(1, null, NOW) }
            // It stays in the routine section, pinned
            assertEquals(Note(1, "a", 100, null, position = 1, pinned = true), runBlocking { dao.byId(1) }?.local())
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
            .addMigrations(*MIGRATIONS)
            .build()
        try {
            val dao = room.noteDao()
            val rows = runBlocking { dao.all() }.sortedBy { it.id }
            val daily = NoteSchedule(ScheduleType.DAILY, minuteOfDay = 540, alert = AlertType.ALARM, leadMinutes = 15)
            assertEquals(
                listOf(Note(1, "plain", 100, null, position = 0), Note(2, "daily", 200, 900, position = 1, schedule = daily)),
                rows.local()
            )
            assertEquals(5, room.openHelper.readableDatabase.version)
            // Nothing is pinned, the routine is in the section by its schedule
            assertEquals(listOf(false, true), rows.map { it.inSection })

            // The new column round-trips with the order
            runBlocking { dao.setPlaces(listOf(rows[0].copy(position = 5, pinned = true)), NOW) }
            assertEquals(Note(1, "plain", 100, null, position = 5, pinned = true), runBlocking { dao.byId(1) }?.local())
            // A note whose schedule goes stays in the section; the rest of the row is left alone
            runBlocking { dao.setSchedule(2, null, NOW) }
            assertEquals(Note(2, "daily", 200, 900, position = 1, pinned = true), runBlocking { dao.byId(2) }?.local())
            // Scheduling or removing a schedule on a note outside the section does not pin it
            runBlocking { dao.insertLast("new", 300) }
            val added = runBlocking { dao.all() }.single { it.text == "new" }
            runBlocking { dao.setSchedule(added.id, null, NOW) }
            assertEquals(false, runBlocking { dao.byId(added.id) }?.pinned)
            runBlocking { dao.setSchedule(added.id, daily, NOW) }
            assertEquals(false, runBlocking { dao.byId(added.id) }?.pinned)
        } finally {
            room.close()
        }
    }

    @Test
    fun storedAlertTypesReadBackAfterFullScreenWasAdded() {
        val room = Room.databaseBuilder(context, NotesDatabase::class.java, DB)
            .addMigrations(*MIGRATIONS)
            .build()
        try {
            // Rows as the version 3 app before the full-screen type wrote them
            room.openHelper.writableDatabase.apply {
                execSQL(
                    "INSERT INTO notes (id, text, createdAt, position, schedule_type, schedule_minuteOfDay, schedule_alert, " +
                            "schedule_leadMinutes, syncId) VALUES (1, 'n', 100, 0, 'DAILY', 540, 'NOTIFICATION', 30, 's1')"
                )
                execSQL(
                    "INSERT INTO notes (id, text, createdAt, position, schedule_type, schedule_minuteOfDay, schedule_alert, " +
                            "schedule_leadMinutes, syncId) VALUES (2, 'a', 100, 1, 'DAILY', 540, 'ALARM', 30, 's2')"
                )
            }
            val dao = room.noteDao()
            assertEquals(AlertType.NOTIFICATION, runBlocking { dao.byId(1) }?.schedule?.alert)
            assertEquals(AlertType.ALARM, runBlocking { dao.byId(2) }?.schedule?.alert)

            val fullScreen = NoteSchedule(ScheduleType.DAILY, minuteOfDay = 540, alert = AlertType.FULL_SCREEN)
            runBlocking { dao.setSchedule(1, fullScreen, NOW) }
            assertEquals(fullScreen, runBlocking { dao.byId(1) }?.schedule)
            assertEquals(AlertType.ALARM, runBlocking { dao.byId(2) }?.schedule?.alert)
        } finally {
            room.close()
        }
    }

    @Test
    fun migrate4To5GivesEveryNoteASyncIdAndALastChange() {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(DB), null).use { db ->
            db.execSQL(V4_CREATE)
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '$V4_HASH')")
            db.execSQL("INSERT INTO notes (id, text, createdAt, strikedAt, position, pinned) VALUES (1, 'plain', 100, NULL, 0, 1)")
            db.execSQL("INSERT INTO notes (id, text, createdAt, strikedAt, position) VALUES (2, 'struck', 200, 900, 1)")
            db.execSQL(
                "INSERT INTO notes (id, text, createdAt, strikedAt, position, schedule_type, schedule_minuteOfDay, " +
                        "schedule_alert, schedule_leadMinutes) VALUES (3, 'daily', 300, NULL, 2, 'DAILY', 540, 'ALARM', 15)"
            )
            db.version = 4
        }

        val room = Room.databaseBuilder(context, NotesDatabase::class.java, DB).addMigrations(*MIGRATIONS).build()
        try {
            val dao = room.noteDao()
            val rows = runBlocking { dao.allWithTombstones() }.sortedBy { it.id }
            assertEquals(5, room.openHelper.readableDatabase.version)
            // Nothing else changed
            val daily = NoteSchedule(ScheduleType.DAILY, minuteOfDay = 540, alert = AlertType.ALARM, leadMinutes = 15)
            assertEquals(
                listOf(
                    Note(1, "plain", 100, null, position = 0, pinned = true),
                    Note(2, "struck", 200, 900, position = 1),
                    Note(3, "daily", 300, null, position = 2, schedule = daily),
                ),
                rows.local()
            )
            // A random version 4 UUID each, the later of creation and strike as the last change, nothing waiting
            rows.forEach { assertTrue(it.syncId, UUID_V4.matches(it.syncId)) }
            assertEquals(3, rows.map { it.syncId }.toSet().size)
            assertEquals(listOf(100L, 900L, 300L), rows.map { it.updatedAt })
            assertTrue(rows.none { it.dirty || it.deletedAt != null })
        } finally {
            room.close()
        }
    }

    @Test
    fun everyWriteWaitsForTheKioskAndDeletesStayAsTombstones() {
        val room = Room.databaseBuilder(context, NotesDatabase::class.java, DB).addMigrations(*MIGRATIONS).build()
        try {
            val dao = room.noteDao()
            runBlocking { dao.insertLast("a", 100) }
            val a = runBlocking { dao.all() }.single()
            assertTrue(UUID_V4.matches(a.syncId))
            assertEquals(100L, a.updatedAt)
            assertTrue(a.dirty)

            runBlocking { dao.markClean(a.id, a.updatedAt) }
            assertFalse(runBlocking { dao.byId(a.id) }!!.dirty)
            runBlocking { dao.toggleStrike(a.id, 200) }
            assertEquals(Note(a.id, "a", 100, 200, position = 0, syncId = a.syncId, updatedAt = 200, dirty = true), runBlocking { dao.byId(a.id) })
            // The kiosk took an older state: the note still waits
            runBlocking { dao.markClean(a.id, 100) }
            assertEquals(1, runBlocking { dao.dirtyCount() })

            runBlocking { dao.delete(a.id, 300) }
            assertEquals(emptyList<Note>(), runBlocking { dao.all() })
            assertEquals(emptyList<Note>(), runBlocking { dao.observeAll().first() })
            assertNull(runBlocking { dao.byId(a.id) })
            val tombstone = runBlocking { dao.allWithTombstones() }.single()
            assertEquals(300L, tombstone.deletedAt)
            assertEquals(300L, tombstone.updatedAt)
            // Kept while it waits for the kiosk, gone once the kiosk has it
            runBlocking { dao.purgeSyncedTombstones() }
            assertEquals(1, runBlocking { dao.allWithTombstones() }.size)
            runBlocking { dao.markClean(a.id, 300) }
            runBlocking { dao.purgeSyncedTombstones() }
            assertEquals(emptyList<Note>(), runBlocking { dao.allWithTombstones() })
        } finally {
            room.close()
        }
    }

    @Test
    fun kioskItemsApplyByTheClockAndANewListRenewsEveryId() {
        val room = Room.databaseBuilder(context, NotesDatabase::class.java, DB).addMigrations(*MIGRATIONS).build()
        try {
            val dao = room.noteDao()
            runBlocking { dao.insertLast("phone", 100) }
            val phone = runBlocking { dao.all() }.single()
            val item = SyncItem("kiosk-1", "from kiosk", true, 150, 4, 120, 150, null, null)
            val changes = runBlocking { dao.applyRemote(listOf(item, item.copy(id = phone.syncId, text = "older", updatedAt = 50)), serverWins = false) }
            // The new item came in (with its row id), the older edit of the phone's note did not
            assertEquals(1, changes.size)
            assertNull(changes.single().before)
            val kiosk = runBlocking { dao.bySyncId("kiosk-1") }!!
            assertEquals(changes.single().after, kiosk)
            assertEquals(Note(kiosk.id, "from kiosk", 120, 150, position = 4, syncId = "kiosk-1", updatedAt = 150), kiosk)
            assertEquals("phone", runBlocking { dao.byId(phone.id) }?.text)

            runBlocking { dao.delete(phone.id, 200) }
            runBlocking { dao.startList() }
            val renewed = runBlocking { dao.allWithTombstones() }.single()
            assertEquals(kiosk.id, renewed.id)
            assertNotEquals("kiosk-1", renewed.syncId)
            assertTrue(renewed.dirty)
        } finally {
            room.close()
        }
    }

    @Test
    fun firstSyncAdoptionKeepsThePhonesNote() {
        val room = Room.databaseBuilder(context, NotesDatabase::class.java, DB).addMigrations(*MIGRATIONS).build()
        try {
            val dao = room.noteDao()
            val daily = NoteSchedule(ScheduleType.DAILY, minuteOfDay = 540, alert = AlertType.ALARM)
            runBlocking { dao.insertLast("Pills", 100) }
            val phone = runBlocking { dao.all() }.single()
            runBlocking { dao.setSchedule(phone.id, daily, 200) }
            runBlocking { dao.setPlaces(listOf(phone.copy(position = 6, pinned = true)), 300) }
            runBlocking { dao.markClean(phone.id, 300) }
            // Newer, meta-less kiosk item with the same text: the phone's note keeps everything and goes out
            val item = SyncItem("k-pills", "Pills", false, null, 0, 50, 900, null, null)
            runBlocking { dao.adopt(listOf(phone.id to item)) }
            assertEquals(
                Note(phone.id, "Pills", 100, null, position = 6, schedule = daily, pinned = true, syncId = "k-pills", updatedAt = 901, dirty = true),
                runBlocking { dao.byId(phone.id) }
            )
            // The item itself then changes nothing
            assertEquals(emptyList<Any>(), runBlocking { dao.applyRemote(listOf(item), serverWins = false) })
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
        const val V4_HASH = "2afdeab028cd9367b4a02df336a8c381"
        const val V4_CREATE = "CREATE TABLE IF NOT EXISTS `notes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`text` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `strikedAt` INTEGER, `position` INTEGER NOT NULL DEFAULT 0, " +
                "`pinned` INTEGER NOT NULL DEFAULT 0, `schedule_type` TEXT, `schedule_minuteOfDay` INTEGER, `schedule_days` INTEGER, " +
                "`schedule_at` INTEGER, `schedule_alert` TEXT, `schedule_leadMinutes` INTEGER)"
        const val NOW = 1_000L
        val MIGRATIONS = arrayOf(
            NotesDatabase.MIGRATION_1_2, NotesDatabase.MIGRATION_2_3, NotesDatabase.MIGRATION_3_4, NotesDatabase.MIGRATION_4_5,
        )
        val UUID_V4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

        /** The note without what the kiosk sync adds, to compare with notes written before it. */
        fun Note.local() = copy(syncId = "", updatedAt = 0, dirty = false)
        fun List<Note>.local() = map { it.local() }

        const val V3_HASH = "8dac1655aedd3b640d79fcc5dd24d9d4"
        const val V3_CREATE = "CREATE TABLE IF NOT EXISTS `notes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`text` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `strikedAt` INTEGER, `position` INTEGER NOT NULL DEFAULT 0, " +
                "`schedule_type` TEXT, `schedule_minuteOfDay` INTEGER, `schedule_days` INTEGER, `schedule_at` INTEGER, " +
                "`schedule_alert` TEXT, `schedule_leadMinutes` INTEGER)"
    }
}
