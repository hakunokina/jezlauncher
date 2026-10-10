package app.olauncher.sync

import app.olauncher.data.Note
import app.olauncher.data.isDoneAt
import app.olauncher.data.nextDayStart
import java.util.TimeZone

/** A note the sync changed: [before] is null for one that came new from the kiosk. */
data class NoteChange(val before: Note?, val after: Note) {
    /** The note had alerts and has none now: deleted on the kiosk, or its schedule went. */
    val dropsAlerts get() = before?.schedule != null && (after.deletedAt != null || after.schedule == null)
}

/**
 * What a kiosk [item] makes of the [local] note with its id (null when the phone has none): the note to store, or
 * null when nothing changes. The later updatedAt wins (last writer wins); [serverWins] hands the kiosk ties too, for
 * its answer to a push. An item deleted on the kiosk that the phone never had stays away.
 */
fun mergeRemote(local: Note?, item: SyncItem, serverWins: Boolean): Note? {
    if (local == null) return if (item.deletedAt == null) item.toNote(null) else null
    if (item.updatedAt < local.updatedAt || (item.updatedAt == local.updatedAt && !serverWins)) return null
    return item.toNote(local).takeIf { it != local }
}

/**
 * The first sync with a list pairs phone notes with kiosk items of the same text that are not linked yet, so both
 * sides end up with one of each instead of a copy. Equal texts pair in order (position, sortOrder). Returns
 * (note id, item id) pairs; the note takes the item's id and the newer of the two wins as usual.
 */
fun matchByText(notes: List<Note>, items: List<SyncItem>): List<Pair<Long, String>> {
    val itemIds = items.mapTo(HashSet()) { it.id }
    val noteIds = notes.mapTo(HashSet()) { it.syncId }
    val freeItems = items.filter { it.deletedAt == null && it.id !in noteIds }
        .sortedWith(compareBy<SyncItem> { it.sortOrder }.thenBy { it.createdAt })
        .groupBy { it.text.trim() }
    return notes.filter { it.deletedAt == null && it.syncId !in itemIds }
        .sortedWith(compareBy<Note> { it.position }.thenBy { it.createdAt }.thenBy { it.id })
        .groupBy { it.text.trim() }
        .flatMap { (text, group) -> group.zip(freeItems[text].orEmpty()) { note, item -> note.id to item.id } }
}

/**
 * The phone's [note] linked to the kiosk [item] of the same text ([matchByText]). The phone's note wins: its text,
 * schedule, pin and place stay, and it goes to the kiosk (dirty, newer than the item) with its schedule in the meta.
 * Only done or not comes from whichever side changed last.
 */
fun adoptedNote(note: Note, item: SyncItem): Note {
    val strikedAt = if (item.updatedAt > note.updatedAt) item.toNote(note).strikedAt else note.strikedAt
    return note.copy(syncId = item.id, strikedAt = strikedAt, updatedAt = maxOf(note.updatedAt, item.updatedAt) + 1, dirty = true)
}

/**
 * Daily and weekly notes the kiosk still shows checked from a period that is over: struck, not done at [now], and
 * not sent since the period ended. They go to the kiosk unchecked as of [now]; the row keeps its strike, as done-ness
 * goes by period on the phone.
 */
fun staleCheckedRoutines(notes: List<Note>, now: Long, zone: TimeZone = TimeZone.getDefault()): List<Note> =
    notes.filter { note ->
        val struck = note.strikedAt ?: return@filter false
        note.deletedAt == null && note.schedule?.repeats == true && !note.isDoneAt(now, zone) &&
                note.updatedAt < nextDayStart(struck, zone)
    }
