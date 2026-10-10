package app.olauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** The routine section of the notes screen: who is in it, where done notes go, dragging in and out, the home notes. */
class NoteSectionTest {

    private val istanbul = TimeZone.getTimeZone("Europe/Istanbul")

    /** Local wall-clock time in Istanbul; months are 1-based. */
    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0) =
        Calendar.getInstance(istanbul).apply {
            clear()
            set(year, month - 1, day, hour, minute)
        }.timeInMillis

    private val daily = NoteSchedule(ScheduleType.DAILY, minuteOfDay = 9 * 60)
    private val weekly = NoteSchedule(ScheduleType.WEEKLY, minuteOfDay = 18 * 60, days = dayBit(Calendar.MONDAY))
    private val once = NoteSchedule(ScheduleType.ONCE, at = at(2026, 10, 20, 9))

    private fun note(id: Long, schedule: NoteSchedule? = null, pinned: Boolean = false, strikedAt: Long? = null, position: Long = id) =
        Note(id = id, text = "n$id", createdAt = id, strikedAt = strikedAt, position = position, schedule = schedule, pinned = pinned)

    /** Note ids with "-" for the done line and "=" for the section line; struck notes get a "~", off-day ones an "o". */
    private fun show(rows: List<NoteRow>) = rows.joinToString(" ") {
        when (it) {
            is NoteRow.Item -> (if (it.note.isStruck) "~" else "") + (if (it.offDay) "o" else "") + it.note.id
            NoteRow.DoneLine -> "-"
            NoteRow.SectionLine -> "="
        }
    }

    private fun shown(notes: List<Note>, now: Long) = show(noteRows(notes, now, istanbul))

    // 2026-10-12 is a Monday
    private val monday = at(2026, 10, 12, 12)

    // Who is in the section

    @Test
    fun scheduledNotesAreInTheSectionByThemselves() {
        val notes = listOf(note(1), note(2, daily), note(3), note(4, once), note(5, weekly))
        assertEquals("- 2 4 5 = 1 3", shown(notes, monday))
    }

    @Test
    fun pinnedUnscheduledNoteIsInTheSection() {
        assertEquals("- 2 = 1 3", shown(listOf(note(1), note(2, pinned = true), note(3)), monday))
        assertTrue(note(1, pinned = true).inSection)
        assertTrue(note(1, daily).inSection)
        assertFalse(note(1).inSection)
    }

    @Test
    fun linesAreThereAlsoAroundAnEmptySection() {
        assertEquals("- =", show(noteRows(emptyList(), monday, istanbul)))
        assertEquals("~1 - = 2", shown(listOf(note(1, strikedAt = monday - 1), note(2)), monday))
    }

    @Test
    fun sectionKeepsItsManualOrder() {
        val notes = listOf(note(1, daily, position = 5), note(2, pinned = true, position = 1), note(3, weekly, position = 3), note(4, position = 0))
        assertEquals("- 2 3 1 = 4", shown(notes, monday))
    }

    // Where done notes go

    @Test
    fun donePinnedNoteGoesUpToTheDoneNotesAndBackWhenRestored() {
        val pinned = note(2, pinned = true)
        val notes = listOf(note(1), pinned, note(3, daily))
        val struck = pinned.toggleStrike(monday)
        assertEquals("~2 - 3 = 1", shown(listOf(note(1), struck, note(3, daily)), monday))
        // Not a routine: it stays done the next day
        assertEquals("~2 - 3 = 1", shown(listOf(note(1), struck, note(3, daily)), at(2026, 10, 13, 12)))
        assertEquals(shown(notes, monday), shown(listOf(note(1), struck.toggleStrike(monday + 1), note(3, daily)), monday))
    }

    @Test
    fun doneDailyNoteStaysStruckInTheSectionUntilTheNextDay() {
        val notes = listOf(note(1, daily, strikedAt = monday - 1), note(2, pinned = true), note(3))
        assertEquals("- ~1 2 = 3", shown(notes, monday))
        assertEquals("- 1 2 = 3", shown(notes, at(2026, 10, 13, 0, 1)))
    }

    @Test
    fun doneWeeklyNoteStaysStruckInTheSectionForItsDayThenGoesOff() {
        val notes = listOf(note(1, weekly, strikedAt = monday - 1))
        assertEquals("- ~1 =", shown(notes, at(2026, 10, 12, 23, 59)))
        // Off its day it shows faint, not struck, in its place in the section
        assertEquals("- o1 =", shown(notes, at(2026, 10, 13, 0, 1)))
        assertEquals("- o1 =", shown(notes, at(2026, 10, 18, 23)))
        assertEquals("- 1 =", shown(notes, at(2026, 10, 19, 0, 1)))
    }

    @Test
    fun doneOneOffNoteGoesUpToTheDoneNotes() {
        // It does not repeat, so it leaves the section like an unscheduled note; restored, it comes back
        val struck = note(1, once, strikedAt = monday - 1)
        assertEquals("~1 - 2 =", shown(listOf(struck, note(2, daily)), monday))
        assertEquals("- 1 2 =", shown(listOf(struck.toggleStrike(monday), note(2, daily)), monday))
    }

    // Dragging in and out

    @Test
    fun draggingANoteAboveTheSectionLinePinsIt() {
        val notes = listOf(note(1, daily), note(2), note(3))
        val rows = noteRows(notes, monday, istanbul)
        assertEquals("- 1 = 2 3", show(rows))
        val moved = moveNoteRow(rows, 4, 2)!!
        assertEquals("- 1 3 = 2", show(moved))
        val changed = reorderedNotes(moved).associateBy { it.id }
        assertEquals(setOf(3L, 2L), changed.keys)
        assertTrue(changed.getValue(3).pinned)
        assertFalse(changed.getValue(2).pinned)
        // Saved, it stays in the section
        assertEquals("- 1 3 = 2", show(noteRows(notes.map { changed[it.id] ?: it }, monday, istanbul)))
    }

    @Test
    fun aNoteCanBeDraggedIntoAnEmptySection() {
        val rows = noteRows(listOf(note(1), note(2)), monday, istanbul)
        assertEquals("- = 1 2", show(rows))
        val moved = moveNoteRow(rows, 3, 1)!!
        assertEquals("- 2 = 1", show(moved))
        assertEquals(mapOf(2L to true, 1L to false), reorderedNotes(moved).associate { it.id to it.pinned })
    }

    @Test
    fun draggingAPinnedNoteBelowTheSectionLineUnpinsIt() {
        val rows = noteRows(listOf(note(1, pinned = true), note(2, daily), note(3)), monday, istanbul)
        assertEquals("- 1 2 = 3", show(rows))
        val moved = moveNoteRow(rows, 1, 3)!!
        assertEquals("- 2 = 1 3", show(moved))
        val changed = reorderedNotes(moved).associateBy { it.id }
        assertFalse(changed.getValue(1).pinned)
        assertEquals(setOf(1L, 2L), changed.keys)
    }

    @Test
    fun scheduledNoteCannotLeaveTheSection() {
        val rows = noteRows(listOf(note(1, daily), note(2)), monday, istanbul)
        assertEquals("- 1 = 2", show(rows))
        assertNull(moveNoteRow(rows, 1, 2))
        assertNull(moveNoteRow(rows, 1, 3))
    }

    @Test
    fun noteCannotBeDraggedIntoTheDoneNotesNorCanADoneRoutineMove() {
        val rows = noteRows(listOf(note(1, strikedAt = monday - 1), note(2, daily, strikedAt = monday - 2), note(3, pinned = true)), monday, istanbul)
        assertEquals("~1 - ~2 3 =", show(rows))
        assertNull(moveNoteRow(rows, 3, 1))
        assertNull(moveNoteRow(rows, 3, 0))
        assertNull(moveNoteRow(rows, 2, 3))
        // An active note moves past a done routine within the section
        assertEquals("~1 - 3 ~2 =", show(moveNoteRow(rows, 3, 2)!!))
    }

    @Test
    fun reorderLeavesTheFlagOfScheduledNotesAlone() {
        val moved = moveNoteRow(noteRows(listOf(note(1, daily, position = 2), note(2, pinned = true, position = 1)), monday, istanbul), 2, 1)!!
        assertEquals("- 1 2 =", show(moved))
        val changed = reorderedNotes(moved).associateBy { it.id }
        assertFalse(changed.getValue(1).pinned)
        assertTrue(changed.getValue(2).pinned)
        assertEquals(1L, changed.getValue(1).position)
        assertEquals(2L, changed.getValue(2).position)
    }

    // Home notes

    @Test
    fun homeShowsTheSectionNotesStillToDoInSectionOrder() {
        val notes = listOf(
            note(1, daily, position = 3),
            note(2, pinned = true, position = 1),
            note(3),
            note(4, weekly, strikedAt = monday - 1, position = 0),
            note(5, pinned = true, strikedAt = monday - 1, position = 2),
            note(6, once, position = 4),
        )
        assertEquals(listOf(2L, 1L, 6L), homeNotes(notes, monday, istanbul).map { it.id })
        // A done routine comes back in its new period; the done pinned note does not
        assertEquals(listOf(4L, 2L, 1L, 6L), homeNotes(notes, at(2026, 10, 19, 12), istanbul).map { it.id })
    }
}
