package app.olauncher.sync

import app.olauncher.data.Note
import app.olauncher.data.NoteDao
import java.util.TimeZone

/** The phone's side of the sync: the notes table, tombstones included. */
interface NoteSyncStore {
    suspend fun everyNote(): List<Note>

    /** See [NoteDao.adopt]: each note id with the kiosk item [matchByText] paired it with. */
    suspend fun adopt(matches: List<Pair<Long, SyncItem>>)

    /** See [NoteDao.applyRemote]. */
    suspend fun applyRemote(items: List<SyncItem>, serverWins: Boolean): List<NoteChange>

    /** The kiosk took these notes as they were sent; changes made since keep them dirty. */
    suspend fun markClean(sent: List<Note>)

    /** The kiosk was told at [now] how these notes show then ([staleCheckedRoutines]). */
    suspend fun markTold(sent: List<Note>, now: Long)

    /** The kiosk ignored these ids (they belong to another list): the notes go again under new ids. */
    suspend fun renewSyncIds(syncIds: List<String>)

    suspend fun purgeSyncedTombstones()
}

class RoomNoteSyncStore(private val dao: NoteDao) : NoteSyncStore {
    override suspend fun everyNote() = dao.allWithTombstones()
    override suspend fun adopt(matches: List<Pair<Long, SyncItem>>) = dao.adopt(matches)
    override suspend fun applyRemote(items: List<SyncItem>, serverWins: Boolean) = dao.applyRemote(items, serverWins)
    override suspend fun markClean(sent: List<Note>) = sent.forEach { dao.markClean(it.id, it.updatedAt) }
    override suspend fun markTold(sent: List<Note>, now: Long) = sent.forEach { dao.markTold(it.id, it.updatedAt, now) }
    override suspend fun renewSyncIds(syncIds: List<String>) =
        syncIds.forEach { syncId -> dao.bySyncId(syncId)?.let { dao.renewSyncId(it.id, newSyncId()) } }

    override suspend fun purgeSyncedTombstones() = dao.purgeSyncedTombstones()
}

/** Where the sync is with the kiosk list. */
interface SyncCursor {
    /** The kiosk's rev up to which the phone has every change. */
    var rev: Long

    /** The list is new to the phone: the next sync takes all of it and matches it with the notes ([matchByText]). */
    var needsMerge: Boolean
}

/**
 * One sync with a kiosk list: pull what changed there, push what changed here (and routines that came back by
 * themselves), pull again so the rev covers the push. Both sides settle every item by last writer wins, so a sync cut
 * off anywhere can simply run again. [announcedRev] is the latest rev the kiosk's event stream reported; a newer one
 * than the pulls saw means another round.
 */
class KioskSyncEngine(
    private val api: KioskApi,
    private val listId: String,
    private val store: NoteSyncStore,
    private val cursor: SyncCursor,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: TimeZone = TimeZone.getDefault(),
    private val announcedRev: () -> Long = { 0 },
) {

    suspend fun sync(): KioskCall<List<NoteChange>> {
        val changes = mutableListOf<NoteChange>()
        repeat(MAX_ROUNDS) {
            pull(changes)?.let { return it }
            val pushed = when (val push = push(changes)) {
                is KioskCall.Ok -> push.value
                else -> return push.asFailure()
            }
            if (!pushed && announcedRev() <= cursor.rev) return done(changes)
        }
        return done(changes)
    }

    private suspend fun done(changes: List<NoteChange>): KioskCall<List<NoteChange>> {
        store.purgeSyncedTombstones()
        return KioskCall.Ok(changes)
    }

    /** Applies the kiosk's changes since the cursor; on a list new to the phone first links equal notes. Null when done. */
    private suspend fun pull(changes: MutableList<NoteChange>): KioskCall<Nothing>? {
        val merge = cursor.needsMerge
        val call = api.items(listId, if (merge) 0 else cursor.rev)
        if (call !is KioskCall.Ok) return call.asFailure()
        val page = call.value
        if (merge) {
            val items = page.items.associateBy { it.id }
            store.adopt(matchByText(store.everyNote(), page.items).map { (id, itemId) -> id to items.getValue(itemId) })
        }
        changes += store.applyRemote(page.items, serverWins = false)
        cursor.rev = page.rev
        if (merge) cursor.needsMerge = false
        return null
    }

    /** Sends the dirty notes and the stale routines; Ok(true) when something went. */
    private suspend fun push(changes: MutableList<NoteChange>): KioskCall<Boolean> {
        val now = clock()
        val notes = store.everyNote()
        val dirty = notes.filter { it.dirty }
        val stale = staleCheckedRoutines(notes, now, zone).filterNot { it.dirty }
        val items = dirty.map { it.toSyncItem(now, zone) } + stale.map { it.toSyncItem(now, zone).copy(updatedAt = now) }
        if (items.isEmpty()) return KioskCall.Ok(false)
        val rejected = mutableListOf<SyncItem>()
        val ignored = mutableListOf<String>()
        for (batch in pushBatches(items)) {
            when (val call = api.push(listId, batch)) {
                is KioskCall.Ok -> {
                    rejected += call.value.rejected
                    ignored += call.value.ignored
                }
                else -> return call.asFailure()
            }
        }
        store.markClean(dirty)
        store.markTold(stale, now)
        changes += store.applyRemote(rejected, serverWins = true)
        store.renewSyncIds(ignored)
        return KioskCall.Ok(true)
    }

    private companion object {
        const val MAX_ROUNDS = 3
    }
}
