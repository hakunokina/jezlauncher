package app.olauncher.sync

import androidx.work.ExistingWorkPolicy
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * When syncs run and queue, apart from Android ([Host]): one sync at a time, the rev the kiosk's event stream
 * announced, another run for what came in while one ran, and the stream's reconnect backoff.
 */
class KioskSyncRunner(private val host: Host, private val sleep: suspend (Long) -> Unit = ::delay) {

    /** The work queue, the notes and the pairing's state. */
    interface Host {
        /** Queues the sync work under [policy]; nothing when not paired. */
        fun enqueue(policy: ExistingWorkPolicy)

        suspend fun dirtyCount(): Int

        /** The kiosk rev the phone has every change up to ([SyncCursor.rev]). */
        val rev: Long

        /** Alerts follow notes the kiosk changed. */
        suspend fun afterRemoteChanges(changes: List<NoteChange>)

        fun record(problem: SyncProblem?)
    }

    /** One sync at a time, and pairing waits for a running one. */
    val lock = Mutex()

    /** The latest rev the event stream announced; 0 until it says one. */
    val announcedRev = AtomicLong(0)

    /** [restart] replaces a queued or running sync (the kiosk just answered: no waiting out a backoff). */
    fun requestSync(restart: Boolean = false) =
        host.enqueue(if (restart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP)

    /**
     * Runs a sync made by [newSync] (null when not paired) up to three times while notes changed during it. A note
     * changed or a change announced after the last look, while the work still runs (where a queued sync is dropped),
     * gets a run queued behind this one.
     */
    suspend fun syncNow(newSync: () -> (suspend () -> KioskCall<List<NoteChange>>)?): KioskCall<List<NoteChange>>? {
        var waiting = 0
        val result = lock.withLock {
            val sync = newSync() ?: return null
            var result: KioskCall<List<NoteChange>>
            var rounds = 0
            do {
                result = sync()
                val changes = (result as? KioskCall.Ok)?.value
                // Applied changes stay applied: a replaced or cancelled run still settles their alerts
                if (!changes.isNullOrEmpty()) withContext(NonCancellable) { host.afterRemoteChanges(changes) }
                waiting = host.dirtyCount()
            } while (result is KioskCall.Ok && waiting > 0 && ++rounds < 3)
            host.record(problemOf(result))
            result
        }
        if (result is KioskCall.Ok && (host.dirtyCount() > waiting || announcedRev.get() > host.rev)) {
            host.enqueue(ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
        return result
    }

    /** A `changed` event of the stream. */
    fun onChanged(rev: Long) {
        // One stream, in order: the latest event is the kiosk's rev (a kiosk that started over announces lower ones)
        announcedRev.set(rev)
        if (rev > host.rev) requestSync()
    }

    /**
     * Follows the stream [events] opens until the token or the list is gone (returned): a sync on every connect (the
     * kiosk does not replay what was missed) and on every change newer than the phone has. Reconnects with backoff,
     * which starts over only once the stream said something (an event or a ping): one that opens and closes right
     * away waits longer each time.
     */
    suspend fun listen(
        events: suspend (onOpen: () -> Unit, onPing: () -> Unit, onEvent: (SseEvent) -> Unit) -> KioskCall<Unit>,
    ): KioskCall<Nothing> {
        var wait = STREAM_RETRY_MIN_MS
        while (true) {
            val result = events(
                {
                    announcedRev.set(0)
                    requestSync(restart = true)
                },
                { wait = STREAM_RETRY_MIN_MS },
            ) { event ->
                wait = STREAM_RETRY_MIN_MS
                changedRev(event)?.let(::onChanged)
            }
            when (result) {
                KioskCall.Unauthorized, KioskCall.ListMissing -> return result.asFailure()
                else -> Unit
            }
            sleep(wait)
            wait = (wait * 2).coerceAtMost(STREAM_RETRY_MAX_MS)
        }
    }

    companion object {
        const val STREAM_RETRY_MIN_MS = 2_000L
        const val STREAM_RETRY_MAX_MS = 60_000L

        fun problemOf(result: KioskCall<*>) = when (result) {
            is KioskCall.Ok -> null
            KioskCall.Unreachable -> SyncProblem.UNREACHABLE
            KioskCall.Unauthorized -> SyncProblem.UNAUTHORIZED
            KioskCall.ListMissing -> SyncProblem.LIST_MISSING
            is KioskCall.Failed -> SyncProblem.FAILED
        }
    }
}
