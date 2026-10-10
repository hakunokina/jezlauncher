package app.olauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** A weekly routine on Monday, Tuesday, Thursday and Friday (the "spor" case): its days, the days off and the change. */
class WeeklyOffDayTest {

    private val istanbul = TimeZone.getTimeZone("Europe/Istanbul")

    /** Local wall-clock time in Istanbul; October 2026, where the 12th is a Monday. */
    private fun oct(day: Int, hour: Int = 0, minute: Int = 0) =
        Calendar.getInstance(istanbul).apply {
            clear()
            set(2026, Calendar.OCTOBER, day, hour, minute)
        }.timeInMillis

    private val sport = NoteSchedule(
        ScheduleType.WEEKLY,
        minuteOfDay = 18 * 60,
        days = listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.THURSDAY, Calendar.FRIDAY).fold(0) { m, d -> m or dayBit(d) },
    )

    private fun spor(strikedAt: Long? = null) = Note(id = 1, text = "spor", createdAt = 1, strikedAt = strikedAt, position = 0, schedule = sport)

    /** "active", "done" or "off" as the note shows at [time]. */
    private fun state(note: Note, time: Long) = when {
        note.isOffDay(time, istanbul) -> "off"
        note.asOf(time, istanbul).isStruck -> "done"
        else -> "active"
    }

    private fun onHome(note: Note, time: Long) = homeNotes(listOf(note), time, istanbul).isNotEmpty()

    @Test
    fun eachDayOfTheWeek() {
        // Mon 12 .. Sun 18, at noon
        val days = (12..18).map { state(spor(), oct(it, 12)) }
        assertEquals(listOf("active", "active", "off", "active", "active", "off", "off"), days)
        assertEquals(listOf(true, true, false, true, true, false, false), (12..18).map { onHome(spor(), oct(it, 12)) })
    }

    @Test
    fun doneOnlyForItsOwnDay() {
        val doneThursday = spor(strikedAt = oct(15, 18, 30))
        assertEquals("done", state(doneThursday, oct(15, 23, 59)))
        assertFalse(onHome(doneThursday, oct(15, 23, 59)))
        // Friday is a day of its own
        assertEquals("active", state(doneThursday, oct(16)))
        assertTrue(onHome(doneThursday, oct(16, 12)))
    }

    @Test
    fun missedFridayDoesNotCarryOverToSaturday() {
        // Not done on Friday: active to the last minute, then off, not overdue
        assertEquals("active", state(spor(), oct(16, 23, 59)))
        assertTrue(onHome(spor(), oct(16, 23, 59)))
        assertEquals("off", state(spor(), oct(17)))
        assertFalse(onHome(spor(), oct(17)))
        assertFalse(spor().isDoneAt(oct(17, 12), istanbul))
        // Done on Friday: off the next day too, not struck
        val doneFriday = spor(strikedAt = oct(16, 19))
        assertEquals("off", state(doneFriday, oct(17)))
        assertFalse(doneFriday.asOf(oct(17), istanbul).isStruck)
    }

    @Test
    fun weekRolloverBringsItBackOnMonday() {
        val doneFriday = spor(strikedAt = oct(16, 19))
        assertEquals("off", state(doneFriday, oct(18, 23, 59)))
        assertEquals("active", state(doneFriday, oct(19)))
        assertTrue(onHome(doneFriday, oct(19)))
    }

    @Test
    fun cannotBeStruckOffItsDay() {
        assertNull(spor().toggledAt(oct(17, 12), istanbul))
        assertNull(spor(strikedAt = oct(16, 19)).toggledAt(oct(17, 12), istanbul))
        assertEquals(oct(16, 12), spor().toggledAt(oct(16, 12), istanbul)!!.strikedAt)
        assertNull(spor(strikedAt = oct(16, 10)).toggledAt(oct(16, 12), istanbul)!!.strikedAt)
        // Other notes are never off
        val daily = spor().copy(schedule = NoteSchedule(ScheduleType.DAILY, minuteOfDay = 9 * 60))
        assertEquals(oct(17, 12), daily.toggledAt(oct(17, 12), istanbul)!!.strikedAt)
        assertEquals(oct(17, 12), spor().copy(schedule = null).toggledAt(oct(17, 12), istanbul)!!.strikedAt)
    }

    @Test
    fun notesScreenKeepsItFaintInTheSection() {
        val notes = listOf(spor(), Note(id = 2, text = "milk", createdAt = 2, position = 1))
        val saturday = noteRows(notes, oct(17, 12), istanbul).filterIsInstance<NoteRow.Item>()
        assertEquals(listOf(true, false), saturday.map { it.offDay })
        assertTrue(saturday.first().note.inSection)
        assertFalse(saturday.first().note.isStruck)
        assertEquals(listOf(false, false), noteRows(notes, oct(16, 12), istanbul).filterIsInstance<NoteRow.Item>().map { it.offDay })
        // It still moves within the section like any active note
        val rows = noteRows(listOf(spor(), Note(id = 3, text = "gym", createdAt = 3, position = 1, pinned = true)), oct(17, 12), istanbul)
        assertEquals(listOf(3L, 1L), moveNoteRow(rows, 1, 2)!!.filterIsInstance<NoteRow.Item>().map { it.note.id })
    }

    @Test
    fun showChangesAtTheMidnightsWhereItGoesOffOrOn() {
        // Friday not done: off at Saturday midnight; Saturday: back on Monday
        assertEquals(oct(17), spor().nextShowChange(oct(16, 12), istanbul))
        assertEquals(oct(19), spor().nextShowChange(oct(17, 12), istanbul))
        // Monday not done: Tuesday is a day of its own, nothing changes until Wednesday
        assertEquals(oct(14), spor().nextShowChange(oct(12, 12), istanbul))
        // Done on Monday: Tuesday brings it back
        assertEquals(oct(13), spor(strikedAt = oct(12, 10)).nextShowChange(oct(12, 12), istanbul))
        // Daily: only a done one changes; one-off and plain notes never do
        val daily = spor().copy(schedule = NoteSchedule(ScheduleType.DAILY, minuteOfDay = 9 * 60))
        assertNull(daily.nextShowChange(oct(12, 12), istanbul))
        assertEquals(oct(13), daily.copy(strikedAt = oct(12, 10)).nextShowChange(oct(12, 12), istanbul))
        assertNull(spor().copy(schedule = NoteSchedule(ScheduleType.ONCE, at = oct(20))).nextShowChange(oct(12, 12), istanbul))
        assertNull(spor().copy(schedule = null).nextShowChange(oct(12, 12), istanbul))
    }

    @Test
    fun alertsComeOnlyOnItsDays() {
        // Saturday after Friday's alert: the next one is Monday's, and Friday's missed run does not hold it back
        assertEquals(oct(19, 18), sport.nextAlert(oct(17, 12), istanbul)!!.dueAt)
        assertTrue(spor(strikedAt = oct(16, 19)).shouldAlert(oct(19, 18), istanbul))
        assertFalse(spor(strikedAt = oct(19, 17)).shouldAlert(oct(19, 18), istanbul))
    }
}
