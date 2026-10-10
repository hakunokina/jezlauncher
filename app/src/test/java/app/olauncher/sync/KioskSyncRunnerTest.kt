package app.olauncher.sync

import androidx.work.ExistingWorkPolicy
import app.olauncher.data.Note
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** When syncs run and queue ([KioskSyncRunner]), against a work queue that drops what WorkManager drops. */
class KioskSyncRunnerTest {

    /** One unique work as WorkManager keeps it: KEEP is dropped while one runs or waits, APPEND_OR_REPLACE queues behind. */
    private class FakeWork : KioskSyncRunner.Host {
        var running = false
        var queued = 0
        var dirty = 0

        /** What the next looks at the dirty notes find, before [dirty]. */
        val dirtyLooks = mutableListOf<Int>()
        override var rev = 0L
        var afterRemote: suspend (List<NoteChange>) -> Unit = {}

        override fun enqueue(policy: ExistingWorkPolicy) {
            queued = when (policy) {
                ExistingWorkPolicy.KEEP -> if (running || queued > 0) queued else 1
                ExistingWorkPolicy.REPLACE -> 1
                else -> queued + 1
            }
        }

        override suspend fun dirtyCount() = if (dirtyLooks.isEmpty()) dirty else dirtyLooks.removeAt(0)
        override suspend fun afterRemoteChanges(changes: List<NoteChange>) = afterRemote(changes)
        override fun record(problem: SyncProblem?) = Unit

        /** The worker takes the queued sync and runs it. */
        suspend fun run(runner: KioskSyncRunner, sync: suspend () -> KioskCall<List<NoteChange>>) {
            assertTrue("a sync is queued", queued > 0)
            queued--
            running = true
            try {
                runner.syncNow { sync }
            } finally {
                running = false
            }
        }
    }

    private val work = FakeWork()

    private fun item(text: String) = NoteChange(null, Note(id = 1, text = text, createdAt = 0))

    private fun changed(rev: Long) = SseEvent("changed", """{"rev":$rev}""")

    @Test
    fun `a change the kiosk announces while a sync settles queues the next sync`() = runBlocking {
        val runner = KioskSyncRunner(work, sleep = {})
        var kioskRev = 0L
        val stream = Channel<Long>(Channel.UNLIMITED)
        val listening = launch {
            runner.listen { _, _, onEvent ->
                for (rev in stream) onEvent(changed(rev))
                KioskCall.Ok(Unit)
            }
        }
        // "milk" is added on the kiosk: its event queues a sync
        kioskRev = 1
        stream.send(1)
        yield()
        assertEquals(1, work.queued)

        // "bread" half a second later, after the sync's last pull, while the alerts of "milk" settle
        work.afterRemote = {
            kioskRev = 2
            stream.send(2)
            yield()
        }
        val pull: suspend () -> KioskCall<List<NoteChange>> = {
            val news = if (kioskRev > work.rev) listOf(item("rev $kioskRev")) else emptyList()
            work.rev = kioskRev
            KioskCall.Ok(news)
        }
        work.run(runner, pull)

        assertEquals("bread's sync waits behind the one that ran", 1, work.queued)
        work.afterRemote = {}
        work.run(runner, pull)
        assertEquals(2L, work.rev)
        assertEquals(0, work.queued)
        listening.cancel()
    }

    @Test
    fun `notes changed during a sync go in another round`() = runBlocking {
        val runner = KioskSyncRunner(work, sleep = {})
        work.queued = 1
        work.dirtyLooks += listOf(1, 0, 0)
        var syncs = 0
        work.run(runner) {
            syncs++
            KioskCall.Ok(emptyList())
        }
        assertEquals(2, syncs)
        assertEquals(0, work.queued)
    }

    @Test
    fun `a note changed after the last look queues the next sync`() = runBlocking {
        val runner = KioskSyncRunner(work, sleep = {})
        work.queued = 1
        // The sync's last look finds nothing; a note is edited before the work ends
        work.dirtyLooks += listOf(0, 1)
        work.run(runner) { KioskCall.Ok(emptyList()) }
        assertEquals(1, work.queued)
    }

    @Test
    fun `a sync that caught up with the kiosk queues nothing`() = runBlocking {
        val runner = KioskSyncRunner(work, sleep = {})
        runner.onChanged(4)
        assertEquals(1, work.queued)
        work.run(runner) {
            work.rev = 4
            KioskCall.Ok(listOf(item("milk")))
        }
        assertEquals(0, work.queued)
    }

    @Test
    fun `a failed sync queues nothing behind`() = runBlocking {
        val runner = KioskSyncRunner(work, sleep = {})
        runner.onChanged(4)
        work.run(runner) { KioskCall.Unreachable }
        assertEquals(0, work.queued)
    }

    @Test
    fun `alerts follow the applied changes even when the sync is cancelled meanwhile`() = runBlocking {
        val runner = KioskSyncRunner(work, sleep = {})
        val gate = CompletableDeferred<Unit>()
        var settled = false
        work.afterRemote = {
            gate.await()
            settled = true
        }
        val sync = launch { runner.syncNow { { KioskCall.Ok(listOf(item("milk"))) } } }
        yield()
        sync.cancel()
        gate.complete(Unit)
        sync.join()
        assertTrue(settled)
        assertTrue("the lock is free again", !runner.lock.isLocked)
    }

    @Test
    fun `a stream that opens and closes right away waits longer each time`() = runBlocking {
        val waits = mutableListOf<Long>()
        val runner = KioskSyncRunner(work) { waits += it }
        var opens = 0
        val gone = runner.listen { onOpen, _, _ ->
            if (++opens > 7) return@listen KioskCall.Unauthorized
            onOpen()
            KioskCall.Ok(Unit)
        }
        assertEquals(KioskCall.Unauthorized, gone)
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), waits)
    }

    @Test
    fun `the stream's backoff starts over once it said something`() = runBlocking {
        val waits = mutableListOf<Long>()
        val runner = KioskSyncRunner(work) { waits += it }
        var opens = 0
        val gone = runner.listen { onOpen, onPing, onEvent ->
            opens++
            if (opens > 5) return@listen KioskCall.ListMissing
            onOpen()
            when (opens) {
                3 -> onPing()
                5 -> onEvent(changed(9))
            }
            KioskCall.Ok(Unit)
        }
        assertEquals(KioskCall.ListMissing, gone)
        assertEquals(listOf(2_000L, 4_000L, 2_000L, 4_000L, 2_000L), waits)
        assertEquals(9L, runner.announcedRev.get())
    }

    @Test
    fun `every connect syncs and forgets what an earlier stream announced`() = runBlocking {
        val runner = KioskSyncRunner(work, sleep = {})
        runner.onChanged(9)
        var opens = 0
        runner.listen { onOpen, _, _ ->
            if (++opens > 1) return@listen KioskCall.Unauthorized
            work.running = true
            onOpen()
            KioskCall.Ok(Unit)
        }
        assertEquals("REPLACE queues even while a sync runs", 1, work.queued)
        assertEquals(0L, runner.announcedRev.get())
    }
}
