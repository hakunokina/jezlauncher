package app.olauncher.sync

import app.olauncher.data.AlertType
import app.olauncher.data.Note
import app.olauncher.data.NoteSchedule
import app.olauncher.data.ScheduleType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** The wire format of the kiosk's sync API and the note ↔ item mapping. */
class SyncItemTest {

    private val utc = TimeZone.getTimeZone("UTC")

    /** 2026-10-10T08:30:00Z, a Saturday */
    private val t0 = 1_791_621_000_000L
    private val day = 24 * 60 * 60_000L

    @Test
    fun isoInstantsRoundTripInJavaScriptsFormat() {
        assertEquals("2026-10-10T08:30:00.000Z", formatIsoInstant(t0))
        assertEquals("2026-10-10T08:30:00.123Z", formatIsoInstant(t0 + 123))
        assertEquals(t0 + 123, parseIsoInstant("2026-10-10T08:30:00.123Z"))
        assertEquals(t0, parseIsoInstant("2026-10-10T08:30:00Z"))
        assertEquals(t0, parseIsoInstant("2026-10-10T11:30:00.000+03:00"))
        assertEquals(t0, parseIsoInstant("2026-10-10T03:30:00-0500"))
        // Fractions past milliseconds are cut
        assertEquals(t0 + 123, parseIsoInstant("2026-10-10T08:30:00.123456789Z"))
        assertEquals(t0 + 500, parseIsoInstant("2026-10-10T08:30:00.5Z"))
        for (bad in listOf("", "2026-10-10", "2026-10-10T08:30:00", "2026-13-10T08:30:00Z", "2026-02-30T08:30:00Z", "yesterday"))
            assertNull(bad, parseIsoInstant(bad))
    }

    @Test
    fun noteGoesOutWithEveryFieldAndComesBackTheSame() {
        val weekly = NoteSchedule(ScheduleType.WEEKLY, minuteOfDay = 18 * 60, days = 0b010_0001, alert = AlertType.FULL_SCREEN, leadMinutes = 15)
        val note = Note(
            id = 7, text = "Gym", createdAt = t0 - day, strikedAt = t0, position = 3, schedule = weekly, pinned = true,
            syncId = "id-1", updatedAt = t0 + 5, dirty = true,
        )
        val item = note.toSyncItem(t0 + 10, utc)
        assertEquals(SyncItem("id-1", "Gym", true, t0, 3, t0 - day, t0 + 5, null, item.meta), item)

        val json = JSONObject(pushBody(listOf(item))).getJSONArray("items").getJSONObject(0)
        assertEquals(setOf("id", "text", "checked", "checkedAt", "sortOrder", "createdAt", "updatedAt", "deletedAt", "meta"), json.keys().asSequence().toSet())
        // Nulls are sent, not left out: the kiosk refuses missing fields
        assertTrue(json.isNull("deletedAt"))
        assertEquals("2026-10-10T08:30:00.005Z", json.getString("updatedAt"))

        val back = parseItemsPage("""{"items":[$json],"rev":12}""")!!
        assertEquals(12L, back.rev)
        assertEquals(item, back.items.single())
        // The phone's copy as it was, apart from the waiting flag
        assertEquals(note.copy(dirty = false), back.items.single().toNote(note))
    }

    @Test
    fun plainNoteHasNoMetaAndAKioskItemBecomesAPlainNote() {
        val note = Note(id = 1, text = "Milk", createdAt = t0, syncId = "a", updatedAt = t0)
        assertNull(note.toSyncItem(t0, utc).meta)
        val item = SyncItem("k1", "Bread", true, null, 4, t0, t0 + 9, null, null)
        val created = item.toNote(null)
        assertEquals(Note(text = "Bread", createdAt = t0, strikedAt = t0 + 9, position = 4, syncId = "k1", updatedAt = t0 + 9), created)
        assertFalse(created.dirty)
    }

    @Test
    fun itemWithoutMetaKeepsThePhonesScheduleAndPin() {
        val daily = NoteSchedule(ScheduleType.DAILY, minuteOfDay = 9 * 60, alert = AlertType.ALARM)
        val local = Note(id = 3, text = "Pills", createdAt = t0, position = 2, schedule = daily, pinned = true, syncId = "p", updatedAt = t0)
        // The kiosk edited the text and never had the meta: the schedule stays, it was not removed
        val edited = SyncItem("p", "Pills!", true, t0 + 5, 7, t0, t0 + 5, null, null).toNote(local)
        assertEquals(daily, edited.schedule)
        assertTrue(edited.pinned)
        assertEquals("Pills!", edited.text)
        assertEquals(t0 + 5, edited.strikedAt)
        // A meta the phone sent still decides
        val unpinned = SyncItem("p", "Pills", false, null, 7, t0, t0 + 5, null, "{\"v\":1,\"pinned\":false}").toNote(local)
        assertNull(unpinned.schedule)
        assertFalse(unpinned.pinned)
        // A note new to the phone has nothing to keep
        assertNull(SyncItem("q", "New", false, null, 0, t0, t0, null, null).toNote(null).schedule)
    }

    @Test
    fun metaKeepsScheduleAndPinAndSurvivesJunk() {
        val once = NoteSchedule(ScheduleType.ONCE, at = t0, alert = AlertType.ALARM, leadMinutes = 0)
        assertEquals(once to false, parseNoteMeta(noteMeta(once, false)))
        assertEquals(null to true, parseNoteMeta(noteMeta(null, true)))
        val daily = NoteSchedule(ScheduleType.DAILY, minuteOfDay = 540)
        assertEquals(daily to true, parseNoteMeta(noteMeta(daily, true)))
        assertNull(noteMeta(null, false))
        for (junk in listOf(null, "", "not json", "[]", """{"schedule":{"type":"YEARLY"}}"""))
            assertEquals(junk, null to false, parseNoteMeta(junk))
        // An alert type this version does not know falls back to a notification
        assertEquals(
            NoteSchedule(ScheduleType.DAILY, minuteOfDay = 60),
            parseNoteMeta("""{"schedule":{"type":"DAILY","minuteOfDay":60,"alert":"HOLOGRAM"}}""").first
        )
    }

    @Test
    fun doneRoutineOfAnEarlierDayGoesUnchecked() {
        val daily = Note(
            id = 1, text = "Pills", createdAt = t0 - 2 * day, strikedAt = t0 - day, syncId = "p",
            schedule = NoteSchedule(ScheduleType.DAILY, minuteOfDay = 540), updatedAt = t0 - day,
        )
        assertTrue(daily.toSyncItem(t0 - day + 60_000, utc).checked)
        val today = daily.toSyncItem(t0, utc)
        assertFalse(today.checked)
        assertNull(today.checkedAt)
    }

    @Test
    fun longTextIsCutToWhatTheKioskTakes() {
        val note = Note(id = 1, text = "x".repeat(12_000), createdAt = t0, syncId = "a", updatedAt = t0)
        assertEquals(10_000, note.toSyncItem(t0, utc).text.length)
    }

    @Test
    fun answersParseAndBrokenOnesDoNot() {
        assertEquals(listOf(KioskList("L1", "Groceries"), KioskList("L2", "")), parseKioskLists("""{"lists":[{"id":"L1","name":"Groceries","color":"#ff0000"},{"id":"L2","name":null}]}"""))
        assertNull(parseKioskLists("""{"lists":[{"name":"no id"}]}"""))
        assertNull(parseKioskLists("<html>"))

        val push = parsePushResult("""{"rev":40,"rejected":[]}""")!!
        assertEquals(PushResult(40, emptyList(), emptyList()), push)
        assertEquals(listOf("x", "y"), parsePushResult("""{"rev":41,"rejected":[],"ignored":["x","y"]}""")?.ignored)
        assertNull(parsePushResult("""{"rejected":[]}"""))

        // An item without an id or a readable updatedAt makes the whole page unusable rather than half applied
        assertNull(parseItemsPage("""{"items":[{"id":"a","text":"t","updatedAt":"later"}],"rev":1}"""))
        assertNull(parseItemsPage("""{"items":[{"text":"t","updatedAt":"2026-10-10T08:30:00Z"}],"rev":1}"""))
        // Missing optional fields get defaults; createdAt falls back to updatedAt
        val sparse = parseItemsPage("""{"items":[{"id":"a","updatedAt":"2026-10-10T08:30:00Z","meta":null}],"rev":1}""")!!.items.single()
        assertEquals(SyncItem("a", "", false, null, 0, t0, t0, null, null), sparse)
    }

    @Test
    fun pushesAreSplitToTheKiosksLimits() {
        val items = (1..1203).map { SyncItem("id$it", "t", false, null, it, t0, t0, null, null) }
        val batches = pushBatches(items)
        assertEquals(listOf(500, 500, 203), batches.map { it.size })
        assertEquals(items, batches.flatten())
        val big = (1..5).map { SyncItem("id$it", "x".repeat(10_000), false, null, it, t0, t0, null, null) }
        val bySize = pushBatches(big, maxBytes = 25_000)
        assertEquals(listOf(2, 2, 1), bySize.map { it.size })
        bySize.forEach { assertTrue(pushBody(it).toByteArray().size < 1_000_000) }
    }
}
