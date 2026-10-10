package app.olauncher.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import app.olauncher.R
import app.olauncher.data.AlertType
import app.olauncher.data.Prefs
import app.olauncher.databinding.ActivityNoteAlarmBinding
import app.olauncher.helper.NoteAlerts
import app.olauncher.helper.timeText

/**
 * Full-screen alarm of an alarm or full-screen note, opened by the alert's full-screen intent, also over the lock
 * screen. The sound belongs to the notification: an alarm rings until one of the buttons removes it, a full-screen
 * alert sounded once, so its screen stays on for a while and Back may leave it in the notification shade.
 */
class NoteAlarmActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNoteAlarmBinding

    // Back would leave an alarm ringing behind the lock screen; one of the buttons decides
    private val blockBack = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = Unit
    }

    private val releaseScreen = Runnable { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The launcher may not have run yet in this process (alarm after a reboot)
        AppCompatDelegate.setDefaultNightMode(Prefs(this).appTheme)
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        binding = ActivityNoteAlarmBinding.inflate(layoutInflater)
        setContentView(binding.root)
        onBackPressedDispatcher.addCallback(this, blockBack)
        bind()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bind()
    }

    override fun onDestroy() {
        binding.root.removeCallbacks(releaseScreen)
        super.onDestroy()
    }

    private fun bind() {
        val noteId = intent.getLongExtra(NoteAlerts.EXTRA_NOTE_ID, -1)
        val dueAt = intent.getLongExtra(NoteAlerts.EXTRA_DUE_AT, 0)
        val alert = AlertType.entries.firstOrNull { it.name == intent.getStringExtra(NoteAlerts.EXTRA_ALERT) } ?: AlertType.ALARM
        binding.alarmText.text = intent.getStringExtra(NoteAlerts.EXTRA_TEXT)
        binding.alarmTime.text = timeText(dueAt)
        binding.alarmSnooze.text = getString(R.string.note_alert_snooze, NoteAlerts.SNOOZE_MINUTES)
        binding.alarmDone.setOnClickListener { send(NoteAlerts.ACTION_DONE, noteId, dueAt) }
        binding.alarmSnooze.setOnClickListener { send(NoteAlerts.ACTION_SNOOZE, noteId, dueAt) }
        binding.alarmDismiss.setOnClickListener { send(NoteAlerts.ACTION_DISMISS, noteId, dueAt) }
        blockBack.isEnabled = alert.ringsUntilDismissed
        // One UI turns the screen off after its 5 s lock screen timeout otherwise
        binding.root.removeCallbacks(releaseScreen)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!alert.ringsUntilDismissed) binding.root.postDelayed(releaseScreen, FULL_SCREEN_ON_MS)
    }

    private fun send(action: String, noteId: Long, dueAt: Long) {
        sendBroadcast(NoteAlerts.receiverIntent(this, action, noteId, dueAt))
        finish()
    }

    private companion object {
        /** How long a full-screen alert keeps the screen on before the normal screen timeout takes over. */
        const val FULL_SCREEN_ON_MS = 60_000L
    }
}
