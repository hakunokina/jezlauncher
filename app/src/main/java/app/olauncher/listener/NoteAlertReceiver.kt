package app.olauncher.listener

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.olauncher.data.NotesDatabase
import app.olauncher.data.asOf
import app.olauncher.data.shouldAlert
import app.olauncher.helper.NoteAlerts
import app.olauncher.sync.KioskSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Note alerts going off ([NoteAlerts]) and the buttons of a showing alert. Not exported: only our pending intents. */
class NoteAlertReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handle(app, intent)
            } finally {
                result.finish()
            }
        }
    }

    private suspend fun handle(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == NoteAlerts.ACTION_PERIOD) {
            NoteAlerts.rescheduleAll(context)
            // The kiosk shows it unchecked again too
            KioskSync.requestSync(context)
            return
        }
        val id = intent.getLongExtra(NoteAlerts.EXTRA_NOTE_ID, -1)
        val dueAt = intent.getLongExtra(NoteAlerts.EXTRA_DUE_AT, 0)
        val dao = NotesDatabase.get(context).noteDao()
        when (action) {
            NoteAlerts.ACTION_ALERT, NoteAlerts.ACTION_SNOOZED -> {
                val note = dao.byId(id)
                // Done in time (or deleted): nothing to show, the next alert still gets armed
                if (note != null && note.shouldAlert(dueAt)) NoteAlerts.show(context, note, dueAt)
                if (action == NoteAlerts.ACTION_ALERT) NoteAlerts.rescheduleAll(context)
            }
            NoteAlerts.ACTION_DONE -> {
                val now = System.currentTimeMillis()
                val note = dao.byId(id)?.asOf(now)
                if (note != null && !note.isStruck) dao.setStrikedAt(id, now, now)
                NoteAlerts.dismiss(context, id)
                NoteAlerts.rescheduleAll(context)
            }
            NoteAlerts.ACTION_SNOOZE -> dao.byId(id)?.let { NoteAlerts.snooze(context, it, dueAt) } ?: NoteAlerts.dismiss(context, id)
            NoteAlerts.ACTION_DISMISS -> NoteAlerts.dismiss(context, id)
        }
    }
}
