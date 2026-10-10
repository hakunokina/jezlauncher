package app.olauncher.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.TimeZone

@Entity(tableName = "notes", indices = [Index(value = ["syncId"], unique = true)])
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val createdAt: Long,
    /** Time the note was struck through; null while the note is active. */
    val strikedAt: Long? = null,
    /** Manual order of active notes, ascending. Kept while struck so restoring puts the note back in its slot. */
    @ColumnInfo(defaultValue = "0") val position: Long = 0,
    /** Null for a plain note; all schedule columns are null then. */
    @Embedded(prefix = "schedule_") val schedule: NoteSchedule? = null,
    /**
     * An unscheduled note the user put in the routine section (or whose schedule was removed there). Scheduled notes
     * are in the section anyway, so the flag only counts while there is no schedule.
     */
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    /** The note's id on the kiosk list it syncs with (a UUID); every stored row has one. */
    @ColumnInfo(defaultValue = "") val syncId: String = "",
    /** Last change of the note, epoch millis; the newer side wins when the phone and the kiosk both changed it. */
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
    /** Deleted at this time: kept as a tombstone until the kiosk has it, hidden everywhere else. */
    val deletedAt: Long? = null,
    /** Changed since the kiosk last took it. */
    @ColumnInfo(defaultValue = "0") val dirty: Boolean = false,
) {
    val isStruck get() = strikedAt != null

    /** In the routine section, the part of the notes screen that the home screen shows too. */
    val inSection get() = pinned || schedule != null

    fun toggleStrike(now: Long) = copy(strikedAt = if (isStruck) null else now)
}

/** A row of the notes screen: a note, or one of the two faint lines around the routine section. */
sealed interface NoteRow {
    /** [offDay]: a weekly note on a day that is not one of its days ([isOffDay]); it shows faint. */
    data class Item(val note: Note, val offDay: Boolean = false) : NoteRow

    /** Between the done notes and the routine section. */
    data object DoneLine : NoteRow

    /** Between the routine section and the other active notes. */
    data object SectionLine : NoteRow
}

/**
 * Where a note shows. A done note goes up to the done notes, except a daily or weekly one: that stays struck in the
 * routine section for the rest of its day.
 */
private enum class NoteBlock { DONE, SECTION, OTHER }

private val Note.block
    get() = when {
        isStruck && schedule?.repeats != true -> NoteBlock.DONE
        inSection -> NoteBlock.SECTION
        else -> NoteBlock.OTHER
    }

/**
 * Done notes first (most recently struck on top), then the routine section, then the other active notes; the section
 * and the others in their manual order (lowest position first, so new notes land last). Ids break ties so the order
 * is stable.
 */
fun orderNotes(notes: List<Note>): List<Note> {
    val blocks = notes.groupBy { it.block }
    val manual = compareBy<Note> { it.position }.thenBy { it.createdAt }.thenBy { it.id }
    return blocks[NoteBlock.DONE].orEmpty().sortedWith(compareByDescending<Note> { it.strikedAt }.thenByDescending { it.id }) +
            blocks[NoteBlock.SECTION].orEmpty().sortedWith(manual) +
            blocks[NoteBlock.OTHER].orEmpty().sortedWith(manual)
}

/**
 * The notes screen at [now]: the notes as they show then ([displayNotes]) with the lines around the routine section.
 * Both lines are always there, also around an empty section, so a note can be dragged across them; the screen decides
 * which ones to draw.
 */
fun noteRows(notes: List<Note>, now: Long, zone: TimeZone = TimeZone.getDefault()): List<NoteRow> {
    val ordered = displayNotes(notes, now, zone)
    val done = ordered.count { it.block == NoteBlock.DONE }
    val section = ordered.count { it.block == NoteBlock.SECTION }
    return ordered.map<Note, NoteRow> { NoteRow.Item(it, it.isOffDay(now, zone)) }.toMutableList().apply {
        add(done + section, NoteRow.SectionLine)
        add(done, NoteRow.DoneLine)
    }
}

/** Position for a new note so it goes below every existing one; [maxPosition] is null for an empty table. */
fun nextNotePosition(maxPosition: Long?) = (maxPosition ?: -1) + 1

/**
 * Moves the note at [from] to [to] in [rows] (a list from [noteRows]). Only active notes move, never up into the done
 * notes; crossing the section line puts an unscheduled note in the routine section or takes it out, while a scheduled
 * note stays in the section. Returns null when the move is not allowed or an index is out of range.
 */
fun moveNoteRow(rows: List<NoteRow>, from: Int, to: Int): List<NoteRow>? {
    if (from !in rows.indices || to !in rows.indices) return null
    val note = (rows[from] as? NoteRow.Item)?.note ?: return null
    if (note.isStruck) return null
    val moved = rows.toMutableList().apply { add(to, removeAt(from)) }
    if (to < moved.indexOf(NoteRow.DoneLine)) return null
    if (note.schedule != null && to > moved.indexOf(NoteRow.SectionLine)) return null
    return moved
}

/**
 * The notes whose place changed in [rows] (after [moveNoteRow]): unscheduled notes above the section line are pinned,
 * those below it are not, and the position values the notes below the done line already hold go back out in row
 * order, so done notes keep their slots in between.
 */
fun reorderedNotes(rows: List<NoteRow>): List<Note> {
    val doneLine = rows.indexOf(NoteRow.DoneLine)
    val sectionLine = rows.indexOf(NoteRow.SectionLine)
    val placed = rows.withIndex().mapNotNull { (i, row) ->
        val note = (row as? NoteRow.Item)?.note ?: return@mapNotNull null
        when {
            i < doneLine -> null
            note.schedule == null -> note to note.copy(pinned = i < sectionLine)
            else -> note to note
        }
    }
    val slots = placed.map { it.first.position }.sorted()
    return placed.mapIndexedNotNull { i, (old, new) ->
        new.copy(position = slots[i]).takeIf { it != old }
    }
}
