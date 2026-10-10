package app.olauncher.sync

import app.olauncher.data.Note
import app.olauncher.data.NoteSchedule
import app.olauncher.data.ScheduleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

/** Last writer wins, the first-sync match by text, and routines that came back by themselves. */
class KioskMergeTest {

    private val utc = TimeZone.getTimeZone("UTC")

    /** 2026-10-10T08:30:00Z, a Saturday */
    private val t0 = 1_791_621_000_000L
    private val day = 24 * 60 * 60_000L

    private fun note(id: Long, text: String, updatedAt: Long = t0, syncId: String = "n$id", position: Long = id, dirty: Boolean = false) =
        Note(id = id, text = text, createdAt = t0 - day, position = position, syncId = syncId, updatedAt = updatedAt, dirty = dirty)

    private fun item(id: String, text: String, updatedAt: Long = t0, sortOrder: Int = 0, deletedAt: Long? = null) =
        SyncItem(id, text, false, null, sortOrder, t0 - day, updatedAt, deletedAt, null)

    @Test
    fun newerSideWins() {
        val local = note(1, "phone", updatedAt = t0, dirty = true)
        assertEquals(local.copy(text = "kiosk", updatedAt = t0 + 1, dirty = false, createdAt = t0 - day, position = 0), mergeRemote(local, item("n1", "kiosk", t0 + 1), false))
        assertNull(mergeRemote(local, item("n1", "kiosk", t0 - 1), false))
        assertNull(mergeRemote(local, item("n1", "kiosk", t0 - 1), true))
    }

    @Test
    fun tiesStayLocalExceptForThePushAnswer() {
        val local = note(1, "same", dirty = true, position = 0)
        assertNull(mergeRemote(local, item("n1", "kiosk"), false))
        // The kiosk kept its copy of a tie: take it, which also clears the waiting flag
        assertEquals(local.copy(text = "kiosk", dirty = false), mergeRemote(local, item("n1", "kiosk"), true))
        // Nothing to write when the answer is what the phone has
        assertNull(mergeRemote(note(1, "same", position = 0), item("n1", "same"), true))
    }

    @Test
    fun deletionsFollowTheClockToo() {
        val local = note(1, "x", position = 0)
        assertEquals(t0 + 1, mergeRemote(local, item("n1", "x", t0 + 1, deletedAt = t0 + 1), false)?.deletedAt)
        // An item deleted on the kiosk that the phone never had stays away; a live one comes in
        assertNull(mergeRemote(null, item("k", "gone", deletedAt = t0), false))
        assertEquals("new", mergeRemote(null, item("k", "new"), false)?.text)
        // A phone delete newer than the kiosk's edit stands
        assertNull(mergeRemote(local.copy(deletedAt = t0 + 5, updatedAt = t0 + 5), item("n1", "edited", t0 + 2), false))
    }

    @Test
    fun firstSyncPairsEqualTextsInOrderAndOnlyUnlinkedOnes() {
        val notes = listOf(
            note(1, "Milk", position = 2), note(2, " Eggs ", position = 0), note(3, "Milk", position = 1),
            note(4, "Tea"), note(5, "Linked", syncId = "k-linked"), note(6, "Gone").copy(deletedAt = t0),
        )
        val items = listOf(
            item("k-milk-b", "Milk", sortOrder = 5), item("k-milk-a", "Milk", sortOrder = 1), item("k-eggs", "Eggs"),
            item("k-linked", "Linked"), item("k-gone", "Gone"), item("k-coffee", "Coffee"), item("k-dead", "Tea", deletedAt = t0),
        )
        // Deleted notes and items, and ones already linked, stay out; texts match after trimming
        assertEquals(setOf(3L to "k-milk-a", 1L to "k-milk-b", 2L to "k-eggs"), matchByText(notes, items).toSet())
    }

    @Test
    fun adoptedNoteKeepsThePhonesFieldsAndGoesOut() {
        val daily = NoteSchedule(ScheduleType.DAILY, minuteOfDay = 540)
        val phone = note(1, "Pills", updatedAt = t0 - day, position = 4).copy(schedule = daily, pinned = true, strikedAt = t0 - day)
        // The kiosk's item is newer and unchecked: done-ness comes from it, everything else from the phone
        val adopted = adoptedNote(phone, item("k-pills", "Pills", updatedAt = t0, sortOrder = 9))
        assertEquals(phone.copy(syncId = "k-pills", strikedAt = null, updatedAt = t0 + 1, dirty = true), adopted)
        // An older kiosk item leaves the strike as the phone has it
        val older = adoptedNote(phone, item("k-pills", "Pills", updatedAt = t0 - 2 * day).copy(checked = true, checkedAt = t0 - 2 * day))
        assertEquals(t0 - day, older.strikedAt)
        assertEquals(t0 - day + 1, older.updatedAt)
        // A newer kiosk check comes over
        assertEquals(t0, adoptedNote(phone.copy(strikedAt = null), item("k", "Pills", t0).copy(checked = true, checkedAt = t0)).strikedAt)
    }

    @Test
    fun onlyLosingTheScheduleOrTheNoteDropsAlerts() {
        val daily = NoteSchedule(ScheduleType.DAILY, minuteOfDay = 540)
        val scheduled = note(1, "Pills").copy(schedule = daily)
        assertEquals(true, NoteChange(scheduled, scheduled.copy(schedule = null)).dropsAlerts)
        assertEquals(true, NoteChange(scheduled, scheduled.copy(deletedAt = t0)).dropsAlerts)
        assertEquals(false, NoteChange(scheduled, scheduled.copy(text = "Pills!")).dropsAlerts)
        assertEquals(false, NoteChange(null, scheduled).dropsAlerts)
        assertEquals(false, NoteChange(note(2, "plain"), note(2, "plain").copy(deletedAt = t0)).dropsAlerts)
    }

    @Test
    fun routineDoneOnAnEarlierDayIsStaleUntilSent() {
        val daily = NoteSchedule(ScheduleType.DAILY, minuteOfDay = 540)
        val struckYesterday = note(1, "Pills").copy(schedule = daily, strikedAt = t0 - day, updatedAt = t0 - day)
        assertEquals(listOf(struckYesterday), staleCheckedRoutines(listOf(struckYesterday), t0, utc))
        // Still the same day: done, nothing to tell
        assertEquals(emptyList<Note>(), staleCheckedRoutines(listOf(struckYesterday), t0 - day + 60_000, utc))
        // Told today already, or struck again today, or not a repeating note
        assertEquals(emptyList<Note>(), staleCheckedRoutines(listOf(struckYesterday.copy(updatedAt = t0 - 60_000)), t0, utc))
        assertEquals(emptyList<Note>(), staleCheckedRoutines(listOf(struckYesterday.copy(strikedAt = t0 - 60_000)), t0, utc))
        val once = struckYesterday.copy(schedule = NoteSchedule(ScheduleType.ONCE, at = t0))
        assertEquals(emptyList<Note>(), staleCheckedRoutines(listOf(once, note(2, "plain").copy(strikedAt = t0 - day)), t0, utc))
        // A weekly note done on its day goes unchecked on the next one, off day or not
        val weekly = struckYesterday.copy(schedule = NoteSchedule(ScheduleType.WEEKLY, minuteOfDay = 540, days = 0b001_0000))
        assertEquals(listOf(weekly), staleCheckedRoutines(listOf(weekly), t0, utc))
    }
}
