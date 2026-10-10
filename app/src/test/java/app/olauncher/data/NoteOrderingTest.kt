package app.olauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteOrderingTest {

    private fun note(id: Long, createdAt: Long, strikedAt: Long? = null, position: Long = createdAt) =
        Note(id = id, text = "n$id", createdAt = createdAt, strikedAt = strikedAt, position = position)

    private fun ids(notes: List<Note>) = orderNotes(notes).map { it.id }

    /** Unscheduled notes show the same at any time. */
    private val NOW = 1_000_000L

    /** Note ids with "-" for the done line and "=" for the section line. */
    private fun show(rows: List<NoteRow>) = rows.joinToString(" ") {
        when (it) {
            is NoteRow.Item -> it.note.id.toString()
            NoteRow.DoneLine -> "-"
            NoteRow.SectionLine -> "="
        }
    }

    @Test
    fun emptyInputGivesEmptyList() {
        assertEquals(emptyList<Note>(), orderNotes(emptyList()))
    }

    @Test
    fun activeNotesFollowPositionNotCreation() {
        assertEquals(listOf(3L, 1L, 2L), ids(listOf(note(1, 100, position = 1), note(2, 200, position = 2), note(3, 300, position = 0))))
    }

    @Test
    fun activeNotesAreChatOrderNewestLast() {
        assertEquals(listOf(1L, 2L, 3L), ids(listOf(note(3, 300), note(1, 100), note(2, 200))))
    }

    @Test
    fun struckNotesGoFirstMostRecentlyStruckOnTop() {
        val notes = listOf(
            note(1, 100),
            note(2, 200, strikedAt = 1_000),
            note(3, 300),
            note(4, 400, strikedAt = 2_000),
        )
        assertEquals(listOf(4L, 2L, 1L, 3L), ids(notes))
    }

    @Test
    fun strikeOrderIgnoresCreationOrder() {
        val notes = listOf(note(1, 100, strikedAt = 5_000), note(2, 200, strikedAt = 4_000))
        assertEquals(listOf(1L, 2L), ids(notes))
    }

    @Test
    fun timestampTiesFallBackToId() {
        assertEquals(listOf(1L, 2L), ids(listOf(note(2, 100), note(1, 100))))
        assertEquals(listOf(1L, 2L), ids(listOf(note(2, 200, position = 5), note(1, 100, position = 5))))
        assertEquals(listOf(2L, 1L), ids(listOf(note(1, 100, 900), note(2, 100, 900))))
    }

    @Test
    fun toggleStrikeSetsThenClearsTimestamp() {
        val struck = note(1, 100).toggleStrike(now = 777)
        assertTrue(struck.isStruck)
        assertEquals(777L, struck.strikedAt)

        val restored = struck.toggleStrike(now = 888)
        assertFalse(restored.isStruck)
        assertNull(restored.strikedAt)
        assertEquals(100L, restored.createdAt)
        assertEquals(100L, restored.position)
    }

    @Test
    fun unstruckNoteReturnsToItsCreationPosition() {
        val a = note(1, 100)
        val b = note(2, 200)
        val c = note(3, 300)
        val struck = b.toggleStrike(now = 1_000)
        assertEquals(listOf(2L, 1L, 3L), ids(listOf(a, struck, c)))

        val restored = struck.toggleStrike(now = 2_000)
        assertEquals(listOf(1L, 2L, 3L), ids(listOf(a, restored, c)))
    }

    @Test
    fun newNoteGoesBelowEveryNoteIncludingStruck() {
        assertEquals(0L, nextNotePosition(null))
        val notes = listOf(note(1, 100, position = 7), note(2, 200, strikedAt = 900, position = 9), note(3, 300, position = 3))
        val added = note(4, 400, position = nextNotePosition(notes.maxOf { it.position }))
        assertEquals(10L, added.position)
        assertEquals(4L, ids(notes + added).last())
    }

    @Test
    fun moveNoteRowReordersWithinActiveBlock() {
        val rows = noteRows(listOf(note(1, 100), note(2, 200), note(3, 300), note(4, 400, strikedAt = 900)), NOW)
        assertEquals("4 - = 1 2 3", show(rows))
        assertEquals("4 - = 3 1 2", show(moveNoteRow(rows, 5, 3)!!))
        assertEquals("4 - = 2 1 3", show(moveNoteRow(rows, 3, 4)!!))
    }

    @Test
    fun activeNoteCannotEnterStruckBlockAndStruckNoteCannotMove() {
        val rows = noteRows(listOf(note(1, 100), note(2, 200), note(3, 300, strikedAt = 900)), NOW)
        assertEquals("3 - = 1 2", show(rows))
        assertNull(moveNoteRow(rows, 3, 0))
        assertNull(moveNoteRow(rows, 3, 1))
        assertNull(moveNoteRow(rows, 0, 3))
        assertNull(moveNoteRow(rows, 1, 3))
        assertNull(moveNoteRow(rows, -1, 3))
        assertNull(moveNoteRow(rows, 3, 9))
    }

    @Test
    fun reorderedPositionsReproduceTheDraggedOrder() {
        val notes = listOf(note(1, 100), note(2, 200), note(3, 300), note(4, 400))
        val moved = moveNoteRow(noteRows(notes, NOW), 5, 2)!!
        val changed = reorderedNotes(moved)
        assertEquals(listOf(4L, 1L, 2L, 3L), changed.map { it.id })
        val saved = notes.map { n -> changed.find { it.id == n.id } ?: n }
        assertEquals(listOf(4L, 1L, 2L, 3L), ids(saved))
    }

    @Test
    fun reorderTouchesOnlyMovedRangeAndLeavesStruckSlots() {
        val notes = listOf(note(1, 100), note(2, 200, strikedAt = 900), note(3, 300), note(4, 400), note(5, 500))
        val moved = moveNoteRow(noteRows(notes, NOW), 5, 4)!!
        assertEquals("2 - = 1 4 3 5", show(moved))
        val changed = reorderedNotes(moved)
        assertEquals(mapOf(4L to 300L, 3L to 400L), changed.associate { it.id to it.position })
        assertTrue(changed.none { it.isStruck })
    }

    @Test
    fun restoredNoteReturnsToItsSlotAfterReorder() {
        // a b c d; strike b; move d above a; restore b → b is back in the second slot: d b a c
        var notes = listOf(note(1, 100), note(2, 200), note(3, 300), note(4, 400))
        notes = notes.map { if (it.id == 2L) it.toggleStrike(now = 1_000) else it }
        val moved = moveNoteRow(noteRows(notes, NOW), 5, 3)!!
        assertEquals("2 - = 4 1 3", show(moved))
        val changed = reorderedNotes(moved)
        notes = notes.map { n -> changed.find { it.id == n.id } ?: n }
        notes = notes.map { if (it.id == 2L) it.toggleStrike(now = 2_000) else it }
        assertEquals(listOf(4L, 2L, 1L, 3L), ids(notes))
    }
}
