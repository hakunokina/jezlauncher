package app.olauncher.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.olauncher.BuildConfig
import app.olauncher.data.NotesDatabase
import app.olauncher.helper.NoteAlerts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/** The kiosk and list the notes sync with. */
data class Pairing(val endpoint: KioskEndpoint, val listId: String, val listName: String)

/** Why the last sync did not go through; shown in the settings. */
enum class SyncProblem { UNREACHABLE, UNAUTHORIZED, LIST_MISSING, FAILED }

/**
 * Pairing and sync state, in its own preferences file that backups leave out (backup_rules, data_extraction_rules):
 * the token stays on this phone.
 */
class KioskSyncPrefs(context: Context) : SyncCursor {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    val pairing: Pairing?
        get() {
            val endpoint = activeEndpoint(BuildConfig.KIOSK_SYNC, prefs.getString(BASE, null), prefs.getString(TOKEN, null))
            val listId = prefs.getString(LIST_ID, null)
            return if (endpoint == null || listId == null) null else Pairing(endpoint, listId, prefs.getString(LIST_NAME, "").orEmpty())
        }

    override var rev: Long
        get() = prefs.getLong(REV, 0)
        set(value) = prefs.edit { putLong(REV, value) }

    override var needsMerge: Boolean
        get() = prefs.getBoolean(NEEDS_MERGE, false)
        set(value) = prefs.edit { putBoolean(NEEDS_MERGE, value) }

    /** When the last sync went through; 0 before the first. */
    val lastSyncAt: Long get() = prefs.getLong(LAST_SYNC_AT, 0)

    val problem: SyncProblem? get() = SyncProblem.entries.firstOrNull { it.name == prefs.getString(PROBLEM, null) }

    fun record(problem: SyncProblem?, now: Long) = prefs.edit {
        putString(PROBLEM, problem?.name)
        if (problem == null) putLong(LAST_SYNC_AT, now)
    }

    /** A new list (or kiosk): its sync starts from scratch and merges with the notes. */
    fun pair(endpoint: KioskEndpoint, list: KioskList) = prefs.edit(commit = true) {
        clear()
        putString(BASE, endpoint.base)
        putString(TOKEN, endpoint.token)
        putString(LIST_ID, list.id)
        putString(LIST_NAME, list.name)
        putBoolean(NEEDS_MERGE, true)
    }

    fun clear() = prefs.edit(commit = true) { clear() }

    /** Keep a reference: preferences hold listeners weakly. */
    fun onChange(action: () -> Unit) = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> action() }
        .also { prefs.registerOnSharedPreferenceChangeListener(it) }

    fun removeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        const val FILE = "kiosk_sync"
        private const val BASE = "BASE"
        private const val TOKEN = "TOKEN"
        private const val LIST_ID = "LIST_ID"
        private const val LIST_NAME = "LIST_NAME"
        private const val REV = "REV"
        private const val NEEDS_MERGE = "NEEDS_MERGE"
        private const val LAST_SYNC_AT = "LAST_SYNC_AT"
        private const val PROBLEM = "PROBLEM"
    }
}

/**
 * Two-way sync of the notes with a list on an OpenSkyLight kiosk in the same tailnet (docs/KIOSK_SYNC.md). Only in
 * builds with BuildConfig.KIOSK_SYNC; elsewhere there is never a [Pairing], so no work, stream or request starts.
 *
 * Every note write lands in the database first; a change there queues a sync (WorkManager, needs a network, backs
 * off while the kiosk does not answer). While the launcher shows, the kiosk's event stream brings its changes within
 * seconds; in the background a sync runs every 15 minutes instead.
 */
object KioskSync {

    private const val WORK_NOW = "kiosk_sync"
    private const val WORK_PERIODIC = "kiosk_sync_periodic"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var pairingState: MutableStateFlow<Pairing?>? = null

    private var runner: KioskSyncRunner? = null

    private fun state(context: Context): MutableStateFlow<Pairing?> = synchronized(this) {
        pairingState ?: MutableStateFlow(KioskSyncPrefs(context).pairing).also { pairingState = it }
    }

    private fun runner(context: Context): KioskSyncRunner = synchronized(this) {
        runner ?: KioskSyncRunner(RunnerHost(context.applicationContext)).also { runner = it }
    }

    private class RunnerHost(private val context: Context) : KioskSyncRunner.Host {
        override fun enqueue(policy: ExistingWorkPolicy) = KioskSync.enqueue(context, policy)
        override suspend fun dirtyCount() = NotesDatabase.get(context).noteDao().dirtyCount()
        override val rev get() = KioskSyncPrefs(context).rev
        override suspend fun afterRemoteChanges(changes: List<NoteChange>) = KioskSync.afterRemoteChanges(context, changes)
        override fun record(problem: SyncProblem?) = KioskSyncPrefs(context).record(problem, System.currentTimeMillis())
    }

    fun pairing(context: Context): StateFlow<Pairing?> = state(context)

    /**
     * Follows every write to the notes, wherever it comes from (screens, alerts): queues a sync when something
     * waits for the kiosk; without a pairing, drops the tombstones nothing needs. While a sync runs, it looks at what
     * still waits when it ends ([syncNow]), so its own writes start nothing.
     */
    fun watch(context: Context, db: NotesDatabase) {
        scope.launch {
            db.invalidationTracker.createFlow("notes").conflate().collect {
                val dao = db.noteDao()
                when {
                    state(context).value == null -> dao.purgeTombstones()
                    runner(context).lock.isLocked || dao.dirtyCount() == 0 -> Unit
                    else -> requestSync(context)
                }
            }
        }
    }

    /** At launch: the periodic sync, and the event stream while the launcher is on screen. */
    fun start(activity: ComponentActivity) {
        if (!BuildConfig.KIOSK_SYNC) return
        val context = activity.applicationContext
        if (state(context).value != null) schedulePeriodic(context)
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) { listen(context) }
        }
    }

    /**
     * Queues a sync: right away with a network, otherwise once there is one; retried with backoff while the kiosk does
     * not answer. [restart] replaces a queued or running one (the kiosk just answered: no waiting out a backoff).
     */
    fun requestSync(context: Context, restart: Boolean = false) = runner(context).requestSync(restart)

    private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
        if (state(context).value == null) return
        val request = OneTimeWorkRequestBuilder<KioskSyncWorker>()
            .setConstraints(networkConstraint())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NOW, policy, request)
    }

    /**
     * Pairs with [endpoint] and syncs [list] from now on; the notes go to it as new items, matched by text first. Runs
     * to the end even when the caller goes (the settings closed): notes moved to a new list but paired with the old
     * one would go there twice.
     */
    suspend fun pair(context: Context, endpoint: KioskEndpoint, list: KioskList) {
        if (!BuildConfig.KIOSK_SYNC) return
        scope.launch {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NOW)
            val runner = runner(context)
            runner.lock.withLock {
                NotesDatabase.get(context).noteDao().startList()
                runner.announcedRev.set(0)
                KioskSyncPrefs(context).pair(endpoint, list)
                state(context).value = KioskSyncPrefs(context).pairing
            }
            schedulePeriodic(context)
            requestSync(context, restart = true)
        }.join()
    }

    /** Stops syncing; the notes stay as they are on both sides. Like [pair], runs to the end once started. */
    suspend fun unpair(context: Context) {
        scope.launch {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NOW)
            WorkManager.getInstance(context).cancelUniqueWork(WORK_PERIODIC)
            runner(context).lock.withLock {
                KioskSyncPrefs(context).clear()
                state(context).value = null
                NotesDatabase.get(context).noteDao().purgeTombstones()
            }
        }.join()
    }

    /** Runs the sync now (the worker); null when not paired. */
    suspend fun syncNow(context: Context): KioskCall<List<NoteChange>>? {
        val runner = runner(context)
        return runner.syncNow {
            state(context).value?.let { pairing ->
                val store = RoomNoteSyncStore(NotesDatabase.get(context).noteDao())
                KioskSyncEngine(
                    KioskApi(pairing.endpoint), pairing.listId, store, KioskSyncPrefs(context), announcedRev = runner.announcedRev::get,
                )::sync
            }
        }
    }

    /** Alerts follow notes the kiosk changed (deleted, done, schedule from another phone). */
    private suspend fun afterRemoteChanges(context: Context, changes: List<NoteChange>) {
        for (change in changes) {
            val before = change.before ?: continue
            if (change.dropsAlerts) NoteAlerts.cancel(context, before.id)
            else if (before.schedule != null && change.after.isStruck) NoteAlerts.dismiss(context, before.id)
        }
        NoteAlerts.rescheduleAll(context)
    }

    /** The paired list's event stream while the caller runs ([KioskSyncRunner.listen]); ends when the token or list is gone. */
    private suspend fun listen(context: Context) {
        state(context).collectLatest { pairing ->
            if (pairing == null) return@collectLatest
            val gone = runner(context).listen { onOpen, onPing, onEvent ->
                KioskApi(pairing.endpoint).events(pairing.listId, onOpen, onPing, onEvent)
            }
            KioskSyncPrefs(context).record(KioskSyncRunner.problemOf(gone), System.currentTimeMillis())
        }
    }

    private fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<KioskSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraint())
            .setInputData(workDataOf(KioskSyncWorker.KEY_PERIODIC to true))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private fun networkConstraint() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
}

class KioskSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = when (KioskSync.syncNow(applicationContext)) {
        null, is KioskCall.Ok -> Result.success()
        // The kiosk is off or out of reach: queued changes wait; the periodic run just tries next time
        KioskCall.Unreachable, is KioskCall.Failed -> if (inputData.getBoolean(KEY_PERIODIC, false)) Result.success() else Result.retry()
        // Needs the user (pair again, choose another list); the settings say so
        KioskCall.Unauthorized, KioskCall.ListMissing -> Result.failure()
    }

    companion object {
        const val KEY_PERIODIC = "periodic"
    }
}
