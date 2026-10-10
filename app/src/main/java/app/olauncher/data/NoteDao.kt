package app.olauncher.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import app.olauncher.sync.NoteChange
import app.olauncher.sync.SyncItem
import app.olauncher.sync.adoptedNote
import app.olauncher.sync.mergeRemote
import app.olauncher.sync.newSyncId
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE deletedAt IS NULL")
    fun observeAll(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE deletedAt IS NULL")
    suspend fun all(): List<Note>

    @Insert
    suspend fun insert(note: Note): Long

    @Query("SELECT * FROM notes WHERE id = :id AND deletedAt IS NULL")
    suspend fun byId(id: Long): Note?

    @Query("SELECT MAX(position) FROM notes")
    suspend fun maxPosition(): Long?

    /** Inserts a note below every existing one. */
    @Transaction
    suspend fun insertLast(text: String, createdAt: Long) = insert(
        Note(
            text = text, createdAt = createdAt, position = nextNotePosition(maxPosition()),
            syncId = newSyncId(), updatedAt = createdAt, dirty = true,
        )
    )

    // Every change sets updatedAt and dirty, so the kiosk sync takes it along

    @Query("UPDATE notes SET strikedAt = :strikedAt, updatedAt = :now, dirty = 1 WHERE id = :id")
    suspend fun setStrikedAt(id: Long, strikedAt: Long?, now: Long)

    /**
     * Strikes the note or restores it, going by how it shows at [now] ([toggledAt]): a daily note struck yesterday is
     * active today, so it gets struck again. Returns the note as it shows afterwards, null when it is gone or is a
     * weekly note off its day (left as it is).
     */
    @Transaction
    suspend fun toggleStrike(id: Long, now: Long): Note? {
        val note = byId(id)?.toggledAt(now) ?: return null
        setStrikedAt(id, note.strikedAt, now)
        return note
    }

    @Update
    suspend fun update(note: Note)

    /**
     * Sets or clears ([schedule] null) the note's schedule, leaving the rest of the row as it is now. A note whose
     * schedule goes stays in the routine section, pinned.
     */
    @Transaction
    suspend fun setSchedule(id: Long, schedule: NoteSchedule?, now: Long) {
        byId(id)?.let {
            update(
                it.copy(
                    schedule = schedule, pinned = if (schedule == null) it.inSection else it.pinned,
                    updatedAt = now, dirty = true,
                )
            )
        }
    }

    @Query("UPDATE notes SET position = :position, pinned = :pinned, updatedAt = :now, dirty = 1 WHERE id = :id")
    suspend fun setPlace(id: Long, position: Long, pinned: Boolean, now: Long)

    /** Writes the positions and pinned flags of [notes] in one transaction. */
    @Transaction
    suspend fun setPlaces(notes: List<Note>, now: Long) = notes.forEach { setPlace(it.id, it.position, it.pinned, now) }

    /** Deletes the note: it stays as a tombstone for the kiosk sync until [purgeSyncedTombstones] or [purgeTombstones]. */
    @Query("UPDATE notes SET deletedAt = :now, updatedAt = :now, dirty = 1 WHERE id = :id")
    suspend fun delete(id: Long, now: Long)

    // Kiosk sync (app.olauncher.sync); these see tombstones too

    @Query("SELECT * FROM notes")
    suspend fun allWithTombstones(): List<Note>

    @Query("SELECT COUNT(*) FROM notes WHERE dirty = 1")
    suspend fun dirtyCount(): Int

    @Query("SELECT * FROM notes WHERE syncId = :syncId")
    suspend fun bySyncId(syncId: String): Note?

    /** The note goes to the kiosk again as a new item under [syncId]. */
    @Query("UPDATE notes SET syncId = :syncId, dirty = 1 WHERE id = :id")
    suspend fun renewSyncId(id: Long, syncId: String)

    /** The kiosk took the note as it was at [updatedAt]; a change made since keeps it dirty. */
    @Query("UPDATE notes SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(id: Long, updatedAt: Long)

    /** The kiosk was told at [now] how the note shows then (a routine that came back by itself), unless it changed since. */
    @Query("UPDATE notes SET updatedAt = :now WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markTold(id: Long, updatedAt: Long, now: Long)

    @Query("DELETE FROM notes WHERE deletedAt IS NOT NULL AND dirty = 0")
    suspend fun purgeSyncedTombstones()

    @Query("DELETE FROM notes WHERE deletedAt IS NOT NULL")
    suspend fun purgeTombstones()

    /**
     * Syncing with a new list: every note goes to it as a new item (fresh ids, all dirty; the first sync matches them
     * to items already there), and the tombstones, which belong to the old list, go.
     */
    @Transaction
    suspend fun startList() {
        purgeTombstones()
        allWithTombstones().forEach { renewSyncId(it.id, newSyncId()) }
    }

    /** Links notes to the kiosk items the first sync matched them with ([adoptedNote]): the phone's note wins. */
    @Transaction
    suspend fun adopt(matches: List<Pair<Long, SyncItem>>) =
        matches.forEach { (id, item) -> byId(id)?.let { update(adoptedNote(it, item)) } }

    /**
     * Applies kiosk items, the newer side winning ([mergeRemote]); [serverWins] gives the kiosk ties too (its answer to
     * a push). Returns the notes that changed.
     */
    @Transaction
    suspend fun applyRemote(items: List<SyncItem>, serverWins: Boolean): List<NoteChange> = items.mapNotNull { item ->
        val local = bySyncId(item.id)
        val merged = mergeRemote(local, item, serverWins) ?: return@mapNotNull null
        if (local == null) NoteChange(null, merged.copy(id = insert(merged))) else NoteChange(local, merged.also { update(it) })
    }
}
