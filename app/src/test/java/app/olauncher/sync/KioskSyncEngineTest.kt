package app.olauncher.sync

import app.olauncher.data.AlertType
import app.olauncher.data.Note
import app.olauncher.data.NoteSchedule
import app.olauncher.data.ScheduleType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.TimeZone

/** Whole syncs between a phone store and a kiosk that answers as OpenSkyLight does ([FakeKiosk]). */
class KioskSyncEngineTest {

    /** The notes table as NoteDao keeps it, in memory. */
    private class FakeStore : NoteSyncStore {
        val notes = mutableListOf<Note>()
        private var nextId = 1L

        fun shown() = notes.filter { it.deletedAt == null }
        fun byText(text: String) = shown().single { it.text == text }

        /** NoteDao.insertLast */
        fun add(text: String, at: Long) =
            Note(id = nextId++, text = text, createdAt = at, position = nextId, syncId = newSyncId(), updatedAt = at, dirty = true)
                .also { notes += it }

        /** Any other NoteDao write: the change, updatedAt and dirty. */
        fun edit(text: String, at: Long, change: (Note) -> Note) = replace(byText(text).id) { change(it).copy(updatedAt = at, dirty = true) }

        fun delete(text: String, at: Long) = edit(text, at) { it.copy(deletedAt = at) }

        /** NoteDao.startList */
        fun startList() {
            notes.removeAll { it.deletedAt != null }
            notes.replaceAll { it.copy(syncId = newSyncId(), dirty = true) }
        }

        private fun replace(id: Long, change: (Note) -> Note) = notes.replaceAll { if (it.id == id) change(it) else it }

        override suspend fun everyNote() = notes.toList()

        override suspend fun adopt(matches: List<Pair<Long, SyncItem>>) =
            matches.forEach { (id, item) -> replace(id) { adoptedNote(it, item) } }

        override suspend fun applyRemote(items: List<SyncItem>, serverWins: Boolean) = items.mapNotNull { item ->
            val local = notes.find { it.syncId == item.id }
            val merged = mergeRemote(local, item, serverWins) ?: return@mapNotNull null
            if (local == null) NoteChange(null, merged.copy(id = nextId++).also { notes += it })
            else NoteChange(local, merged.also { replace(local.id) { merged } })
        }

        override suspend fun markClean(sent: List<Note>) =
            sent.forEach { s -> replace(s.id) { if (it.updatedAt == s.updatedAt) it.copy(dirty = false) else it } }

        override suspend fun markTold(sent: List<Note>, now: Long) =
            sent.forEach { s -> replace(s.id) { if (it.updatedAt == s.updatedAt) it.copy(updatedAt = now) else it } }

        override suspend fun renewSyncIds(syncIds: List<String>) = syncIds.forEach { syncId ->
            notes.find { it.syncId == syncId }?.let { note -> replace(note.id) { it.copy(syncId = newSyncId(), dirty = true) } }
        }

        override suspend fun purgeSyncedTombstones() {
            notes.removeAll { it.deletedAt != null && !it.dirty }
        }
    }

    private class MemoryCursor(override var rev: Long = 0, override var needsMerge: Boolean = true) : SyncCursor

    private val utc = TimeZone.getTimeZone("UTC")

    /** 2026-10-10T08:30:00Z, a Saturday */
    private val t0 = 1_791_621_000_000L
    private val hour = 60 * 60_000L
    private val day = 24 * hour

    private val kiosk = FakeKiosk()
    private val store = FakeStore()
    private val cursor = MemoryCursor()
    private var now = t0

    private fun sync(endpoint: KioskEndpoint = kiosk.endpoint) = runBlocking {
        KioskSyncEngine(KioskApi(endpoint), FakeKiosk.LIST, store, cursor, clock = { now }, zone = utc).sync()
    }

    /** Phone has Milk and Eggs, kiosk has Milk and Bread; paired and synced once. */
    private fun paired() {
        store.add("Milk", t0 - 5 * hour)
        store.add("Eggs", t0 - 4 * hour)
        kiosk.add("k-milk", "Milk", t0 - 3 * hour)
        kiosk.add("k-bread", "Bread", t0 - 2 * hour)
        store.startList()
        assertTrue(sync() is KioskCall.Ok)
    }

    @After
    fun close() = kiosk.close()

    @Test
    fun firstSyncMergesBothSidesWithoutCopies() {
        paired()
        assertEquals(listOf("Bread", "Eggs", "Milk"), store.shown().map { it.text }.sorted())
        assertEquals(listOf("Bread", "Eggs", "Milk"), kiosk.texts().sorted())
        // The phone's Milk took the kiosk's id: one Milk on both sides
        assertEquals("k-milk", store.byText("Milk").syncId)
        assertFalse(cursor.needsMerge)
        assertEquals(kiosk.rev, cursor.rev)
        assertTrue(store.notes.none { it.dirty })
    }

    @Test
    fun firstSyncKeepsThePhonesScheduleWhenTheKioskItemIsNewer() {
        val daily = NoteSchedule(ScheduleType.DAILY, minuteOfDay = 9 * 60, alert = AlertType.ALARM)
        now = t0 - 7 * day
        store.add("Pills", now)
        store.add("Other", now)
        store.edit("Pills", now) { it.copy(schedule = daily, pinned = true, position = 5) }
        // Added on the kiosk yesterday: no meta, another place in its list
        kiosk.add("k-pills", "Pills", t0 - day, sortOrder = 0)
        store.startList()
        now = t0
        val result = sync() as KioskCall.Ok

        val pills = store.byText("Pills")
        assertEquals("k-pills", pills.syncId)
        assertEquals(daily, pills.schedule)
        assertTrue(pills.pinned)
        assertEquals(5L, pills.position)
        assertFalse(pills.dirty)
        // Nothing the sync did drops the note's alarm
        assertTrue(result.value.none { it.dropsAlerts })
        // The phone's note went to the kiosk, schedule and pin in the meta
        assertEquals(daily to true, parseNoteMeta(kiosk.item("k-pills")?.getString("meta")))
        assertEquals(listOf("Other", "Pills"), kiosk.texts().sorted())
        // Pulled back later, the item keeps it so
        assertTrue(sync() is KioskCall.Ok)
        assertEquals(daily, store.byText("Pills").schedule)
    }

    @Test
    fun theSyncsOwnWritesStartNoExtraRound() {
        paired()
        kiosk.add("k-butter", "Butter", t0 + hour)
        now = t0 + 2 * hour
        store.add("Tea", now)
        val before = kiosk.requestCount
        assertTrue(sync() is KioskCall.Ok)
        // Pull (applies Butter), push (Tea), pull again; nothing waits afterwards, so no further round
        assertEquals(3, kiosk.requestCount - before)
        assertTrue(store.notes.none { it.dirty })
    }

    @Test
    fun phoneChangesReachTheKiosk() {
        paired()
        now = t0 + hour
        store.edit("Milk", now) { it.copy(strikedAt = now) }
        store.add("Tea", now)
        store.delete("Eggs", now)
        val eggs = store.notes.single { it.text == "Eggs" }
        assertTrue(sync() is KioskCall.Ok)

        assertEquals(true, kiosk.item("k-milk")?.getBoolean("checked"))
        assertEquals(formatIsoInstant(now), kiosk.item("k-milk")?.getString("checkedAt"))
        assertTrue("Tea" in kiosk.texts())
        assertEquals(formatIsoInstant(now), kiosk.itemOf(eggs)?.getString("deletedAt"))
        // The tombstone went once the kiosk had it; nothing waits
        assertTrue(store.notes.none { it.text == "Eggs" })
        assertTrue(store.notes.none { it.dirty })
    }

    @Test
    fun kioskChangesReachThePhone() {
        paired()
        kiosk.add("k-butter", "Butter", t0 + hour)
        kiosk.check("k-milk", t0 + hour)
        kiosk.delete("k-bread", t0 + hour)
        now = t0 + 2 * hour
        val result = sync() as KioskCall.Ok

        assertEquals(listOf("Butter", "Eggs", "Milk"), store.shown().map { it.text }.sorted())
        assertEquals(t0 + hour, store.byText("Milk").strikedAt)
        assertTrue(store.notes.none { it.text == "Bread" })
        assertTrue(store.notes.none { it.dirty })
        assertEquals(setOf("Butter", "Milk", "Bread"), result.value.map { it.after.text }.toSet())
        assertNull(result.value.single { it.after.text == "Butter" }.before)
    }

    @Test
    fun newerEditWinsOnEitherSide() {
        paired()
        store.edit("Milk", t0 + hour) { it.copy(text = "Milk (phone)") }
        kiosk.edit("k-milk", t0 + 2 * hour) { put("text", "Milk (kiosk)") }
        store.edit("Eggs", t0 + 3 * hour) { it.copy(text = "Eggs (phone)") }
        val eggs = store.byText("Eggs (phone)")
        kiosk.edit(eggs.syncId, t0 + 2 * hour) { put("text", "Eggs (kiosk)") }
        now = t0 + 4 * hour
        assertTrue(sync() is KioskCall.Ok)

        assertEquals(listOf("Bread", "Eggs (phone)", "Milk (kiosk)"), store.shown().map { it.text }.sorted())
        assertEquals(listOf("Bread", "Eggs (phone)", "Milk (kiosk)"), kiosk.texts().sorted())
        assertTrue(store.notes.none { it.dirty })
    }

    @Test
    fun kioskEditDuringThePushComesBackAndIsApplied() {
        paired()
        store.edit("Milk", t0 + hour) { it.copy(text = "Oat milk") }
        // Between the phone's pull and its push, someone edits the item on the kiosk
        kiosk.beforePush = { kiosk.edit("k-milk", t0 + 2 * hour) { put("text", "Soy milk") } }
        now = t0 + 3 * hour
        assertTrue(sync() is KioskCall.Ok)

        assertEquals("Soy milk", kiosk.item("k-milk")?.getString("text"))
        assertEquals("k-milk", store.byText("Soy milk").syncId)
        assertTrue(store.notes.none { it.dirty })
    }

    @Test
    fun changesWaitWhileTheKioskIsOffAndGoLater() {
        paired()
        // Nothing listens there: the kiosk is off, or Tailscale is
        val off = KioskEndpoint("http://127.0.0.1:${ServerSocket(0).use { it.localPort }}", FakeKiosk.TOKEN)
        now = t0 + hour
        store.add("Tea", now)
        assertEquals(KioskCall.Unreachable, sync(off))
        assertTrue(store.byText("Tea").dirty)

        assertTrue(sync() is KioskCall.Ok)
        assertTrue("Tea" in kiosk.texts())
        assertFalse(store.byText("Tea").dirty)
    }

    @Test
    fun idsTheKioskKnowsFromAnotherListGoAgainUnderNewIds() {
        paired()
        val tea = store.add("Tea", t0 + hour)
        kiosk.foreignIds += tea.syncId
        now = t0 + hour
        assertTrue(sync() is KioskCall.Ok)

        val synced = store.byText("Tea")
        assertNotEquals(tea.syncId, synced.syncId)
        assertEquals("Tea", kiosk.item(synced.syncId)?.getString("text"))
        assertFalse(synced.dirty)
    }

    @Test
    fun routineDoneYesterdayGoesUncheckedOnce() {
        now = t0 - day + hour
        store.add("Pills", now)
        store.edit("Pills", now) { it.copy(schedule = NoteSchedule(ScheduleType.DAILY, minuteOfDay = 9 * 60), strikedAt = now) }
        store.startList()
        assertTrue(sync() is KioskCall.Ok)
        val pills = store.byText("Pills")
        assertEquals(true, kiosk.item(pills.syncId)?.getBoolean("checked"))

        // Next day: the kiosk shows it unchecked, the phone keeps yesterday's strike (done-ness goes by day)
        now = t0
        val pushes = kiosk.pushes.size
        assertTrue(sync() is KioskCall.Ok)
        assertEquals(false, kiosk.item(pills.syncId)?.getBoolean("checked"))
        assertEquals(t0 - day + hour, store.byText("Pills").strikedAt)
        assertEquals(pushes + 1, kiosk.pushes.size)
        // Told once is enough
        assertTrue(sync() is KioskCall.Ok)
        assertEquals(pushes + 1, kiosk.pushes.size)
    }

    @Test
    fun aKioskCheckTodayIsNotUndoneByYesterdaysStrike() {
        now = t0 - day + hour
        store.add("Pills", now)
        store.edit("Pills", now) { it.copy(schedule = NoteSchedule(ScheduleType.DAILY, minuteOfDay = 9 * 60), strikedAt = now) }
        store.startList()
        assertTrue(sync() is KioskCall.Ok)
        val pills = store.byText("Pills")

        kiosk.check(pills.syncId, t0 + hour)
        now = t0 + 2 * hour
        assertTrue(sync() is KioskCall.Ok)
        assertEquals(true, kiosk.item(pills.syncId)?.getBoolean("checked"))
        assertEquals(t0 + hour, store.byText("Pills").strikedAt)
    }

    @Test
    fun refusedTokenAndDeletedListAreReported() {
        paired()
        kiosk.listGone = true
        assertEquals(KioskCall.ListMissing, sync())
        kiosk.listGone = false
        kiosk.token = "rotated"
        assertEquals(KioskCall.Unauthorized, sync())
    }

    @Test
    fun pushesCarryEveryFieldTheKioskRequires() {
        paired()
        val sent = kiosk.pushes.flatMap { batch -> (0 until batch.length()).map { batch.getJSONObject(it) } }
        assertTrue(sent.isNotEmpty())
        // The fake kiosk answers 400 to a missing field; every push went through, and nulls are sent as null
        assertTrue(sent.all { it.has("meta") && it.has("deletedAt") && it.has("checkedAt") })
    }
}
