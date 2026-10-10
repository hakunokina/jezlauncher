package app.olauncher.data

import android.view.Gravity

/** The lines of home notes on each side of the home screen, top to bottom. */
data class HomeNoteColumns(val start: List<String>, val end: List<String>)

/**
 * Puts the home notes across from the apps: on the right of left-aligned apps, on the left of right-aligned ones and
 * on both sides of centered ones, alternating from the left. At most [max] lines; when notes are left over, the last
 * line says how many as "+n".
 */
fun homeNoteColumns(texts: List<String>, appsGravity: Int, max: Int = MAX_HOME_NOTES): HomeNoteColumns {
    val lines = if (texts.size <= max) texts else texts.take(max - 1) + "+${texts.size - max + 1}"
    return when (appsGravity) {
        Gravity.END -> HomeNoteColumns(lines, emptyList())
        Gravity.CENTER -> HomeNoteColumns(lines.filterIndexed { i, _ -> i % 2 == 0 }, lines.filterIndexed { i, _ -> i % 2 == 1 })
        else -> HomeNoteColumns(emptyList(), lines)
    }
}

/**
 * How many rows a column of [noteRows] home notes takes so that its rows line up with the [appRows] app rows: the notes
 * start on the first app row and the rest of the column is left empty. A vertically [centered] column also needs as
 * many spare rows above the apps as below them, or it sits half a row off.
 */
fun homeNoteRowCount(appRows: Int, noteRows: Int, centered: Boolean): Int {
    val rows = maxOf(appRows, noteRows)
    return if (centered && appRows > 0 && (rows - appRows) % 2 != 0) rows + 1 else rows
}

/** As many as the home apps can be. */
const val MAX_HOME_NOTES = 8
