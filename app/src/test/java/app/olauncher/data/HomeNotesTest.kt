package app.olauncher.data

import android.view.Gravity
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeNotesTest {

    private val five = listOf("a", "b", "c", "d", "e")

    @Test
    fun notesGoAcrossFromTheApps() {
        assertEquals(HomeNoteColumns(emptyList(), five), homeNoteColumns(five, Gravity.START))
        assertEquals(HomeNoteColumns(five, emptyList()), homeNoteColumns(five, Gravity.END))
    }

    @Test
    fun centeredAppsGetNotesOnBothSidesFromTheLeft() {
        assertEquals(HomeNoteColumns(listOf("a", "c", "e"), listOf("b", "d")), homeNoteColumns(five, Gravity.CENTER))
        assertEquals(HomeNoteColumns(listOf("a"), emptyList()), homeNoteColumns(listOf("a"), Gravity.CENTER))
    }

    @Test
    fun notesThatDoNotFitShowAsACount() {
        val ten = (1..10).map { "n$it" }
        assertEquals((1..7).map { "n$it" } + "+3", homeNoteColumns(ten, Gravity.START).end)
        assertEquals((1..8).map { "n$it" }, homeNoteColumns(ten.take(8), Gravity.START).end)
        assertEquals(listOf("n2", "n4", "n6", "+3"), homeNoteColumns(ten, Gravity.CENTER).end)
    }

    @Test
    fun noteRowsLineUpWithTheAppRows() {
        // Fewer notes than apps: the column is as tall as the apps and the notes start on the first app row
        assertEquals(4, homeNoteRowCount(appRows = 4, noteRows = 2, centered = true))
        assertEquals(4, homeNoteRowCount(appRows = 4, noteRows = 2, centered = false))
        // More notes: centered columns need even spare rows to stay level with the apps
        assertEquals(6, homeNoteRowCount(appRows = 4, noteRows = 6, centered = true))
        assertEquals(6, homeNoteRowCount(appRows = 4, noteRows = 5, centered = true))
        assertEquals(5, homeNoteRowCount(appRows = 4, noteRows = 5, centered = false))
        // No apps, nothing to line up with
        assertEquals(3, homeNoteRowCount(appRows = 0, noteRows = 3, centered = true))
    }
}
