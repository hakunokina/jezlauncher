package app.olauncher.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes")
    fun observeAll(): Flow<List<Note>>

    @Query("SELECT * FROM notes")
    suspend fun all(): List<Note>

    @Insert
    suspend fun insert(note: Note)

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun byId(id: Long): Note?

    @Query("SELECT MAX(position) FROM notes")
    suspend fun maxPosition(): Long?

    /** Inserts a note below every existing one. */
    @Transaction
    suspend fun insertLast(text: String, createdAt: Long) =
        insert(Note(text = text, createdAt = createdAt, position = nextNotePosition(maxPosition())))

    @Query("UPDATE notes SET strikedAt = :strikedAt WHERE id = :id")
    suspend fun setStrikedAt(id: Long, strikedAt: Long?)

    /**
     * Strikes the note or restores it, going by how it shows at [now] ([toggledAt]): a daily note struck yesterday is
     * active today, so it gets struck again. Returns the note as it shows afterwards, null when it is gone or is a
     * weekly note off its day (left as it is).
     */
    @Transaction
    suspend fun toggleStrike(id: Long, now: Long): Note? {
        val note = byId(id)?.toggledAt(now) ?: return null
        setStrikedAt(id, note.strikedAt)
        return note
    }

    @Update
    suspend fun update(note: Note)

    /**
     * Sets or clears ([schedule] null) the note's schedule, leaving the rest of the row as it is now. A note whose
     * schedule goes stays in the routine section, pinned.
     */
    @Transaction
    suspend fun setSchedule(id: Long, schedule: NoteSchedule?) {
        byId(id)?.let { update(it.copy(schedule = schedule, pinned = if (schedule == null) it.inSection else it.pinned)) }
    }

    @Query("UPDATE notes SET position = :position, pinned = :pinned WHERE id = :id")
    suspend fun setPlace(id: Long, position: Long, pinned: Boolean)

    /** Writes the positions and pinned flags of [notes] in one transaction. */
    @Transaction
    suspend fun setPlaces(notes: List<Note>) = notes.forEach { setPlace(it.id, it.position, it.pinned) }

    @Delete
    suspend fun delete(note: Note)
}
