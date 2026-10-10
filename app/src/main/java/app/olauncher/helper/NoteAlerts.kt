package app.olauncher.helper

import android.Manifest
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import app.olauncher.MainActivity
import app.olauncher.R
import app.olauncher.data.AlertLaunch
import app.olauncher.data.AlertType
import app.olauncher.data.Note
import app.olauncher.data.NotesDatabase
import app.olauncher.data.nextAlert
import app.olauncher.data.nextShowChange
import app.olauncher.listener.NoteAlertReceiver
import app.olauncher.ui.NoteAlarmActivity

/**
 * Alerts of scheduled notes. Each scheduled note has one pending alarm, for its next alert only; when it fires,
 * [NoteAlertReceiver] shows the alert unless the note is done by then, and arms the next one. Alarms do not survive
 * a reboot, a clock or time zone change or an app update, so those call [rescheduleAll].
 *
 * A second, inexact alarm wakes the app when a done daily or weekly note comes back (a new period starts), so the
 * watch's copy of the list shows it active again even while the phone's notes screen is closed.
 */
object NoteAlerts {

    const val SNOOZE_MINUTES = 10

    /** Receiver actions: a scheduled or snoozed alert going off, the alert's buttons, a new period starting. */
    const val ACTION_ALERT = "alert"
    const val ACTION_SNOOZED = "snoozed"
    const val ACTION_DONE = "done"
    const val ACTION_SNOOZE = "snooze"
    const val ACTION_DISMISS = "dismiss"
    const val ACTION_PERIOD = "period"

    const val EXTRA_NOTE_ID = "noteId"
    const val EXTRA_DUE_AT = "dueAt"
    const val EXTRA_TEXT = "text"
    const val EXTRA_ALERT = "alert"

    private const val CHANNEL_REMINDERS = "note_reminders"
    private const val CHANNEL_ALARMS = "note_alarms"
    private const val CHANNEL_FULL_SCREEN = "note_fullscreen"
    private const val NOTIFICATION_TAG = "note"
    private const val PI_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    /** Arms the next alert of every scheduled note and the period refresh. */
    suspend fun rescheduleAll(context: Context) {
        val notes = NotesDatabase.get(context).noteDao().all()
        val now = System.currentTimeMillis()
        notes.filter { it.schedule != null }.forEach { arm(context, it, now) }
        armPeriodRefresh(context, notes, now)
    }

    /** After a note was struck or restored: a done note's showing alert and snooze go, alarms follow the new state. */
    suspend fun onStrikeChanged(context: Context, note: Note) {
        if (note.isStruck) dismiss(context, note.id)
        rescheduleAll(context)
    }

    /** Drops everything pending for a deleted or unscheduled note. */
    fun cancel(context: Context, noteId: Long) {
        cancelAlarm(context, alertIntent(context, noteId, ACTION_ALERT))
        dismiss(context, noteId)
    }

    /** Removes the note's alert from the screen and its snooze. */
    fun dismiss(context: Context, noteId: Long) {
        cancelAlarm(context, alertIntent(context, noteId, ACTION_SNOOZED))
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_TAG, noteId.toInt())
    }

    fun snooze(context: Context, note: Note, dueAt: Long) {
        dismiss(context, note.id)
        val at = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L
        setAlarm(context, at, alertIntent(context, note.id, ACTION_SNOOZED, dueAt), note.schedule?.alert == AlertType.ALARM)
    }

    /** Without the exact alarm permission alerts still come, but Android may hold them back for a while. */
    fun canScheduleExact(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** Alarm and full-screen notes need the full-screen permission (Android 14+) to open over the lock screen. */
    fun canUseFullScreen(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    /**
     * An alarm notification rings until Done, Snooze or Dismiss; a reminder sounds once. A full-screen alert sounds
     * once too, on its own channel, and opens the alarm screen like an alarm: by the full-screen intent when the screen
     * is off or locked, started directly while the phone is in use.
     */
    fun show(context: Context, note: Note, dueAt: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        createChannels(context)
        val type = note.schedule?.alert ?: AlertType.NOTIFICATION
        val alarm = type == AlertType.ALARM
        val launch = type.launch(
            interactive = context.getSystemService(PowerManager::class.java).isInteractive,
            locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked,
            home = isOlauncherDefault(context),
        )
        val channel = when (type) {
            AlertType.NOTIFICATION -> CHANNEL_REMINDERS
            AlertType.FULL_SCREEN -> CHANNEL_FULL_SCREEN
            AlertType.ALARM -> CHANNEL_ALARMS
        }
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(note.text)
            .setContentText(context.timeText(dueAt))
            .setWhen(dueAt)
            .setShowWhen(true)
            .setPriority(if (alarm) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setDefaults(if (alarm) 0 else NotificationCompat.DEFAULT_ALL)
            .addAction(0, context.getString(R.string.note_alert_done), actionIntent(context, ACTION_DONE, note.id, dueAt))
            .addAction(
                0, context.getString(R.string.note_alert_snooze, SNOOZE_MINUTES),
                actionIntent(context, ACTION_SNOOZE, note.id, dueAt)
            )
        when (type) {
            AlertType.NOTIFICATION -> builder.setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(launcherIntent(context))
            // Not ongoing: it does not ring, so the notification can stay or be swiped away like a reminder
            AlertType.FULL_SCREEN -> builder.setCategory(NotificationCompat.CATEGORY_REMINDER)
                // Ungrouped, it would be auto-grouped with alerts still in the shade and silenced (Android 16):
                // no sound, no heads-up
                .setGroup("$NOTIFICATION_TAG${note.id}")
                .openAlarmScreen(context, note, dueAt, launch)
            AlertType.ALARM -> builder.setCategory(NotificationCompat.CATEGORY_ALARM)
                // Below Android 8 there are no channels to carry the alarm sound
                .setSound(Settings.System.DEFAULT_ALARM_ALERT_URI, AudioManager.STREAM_ALARM)
                .setOngoing(true)
                .openAlarmScreen(context, note, dueAt, launch)
        }
        val notification = builder.build()
        // The alarm sound and vibration repeat until the notification goes
        if (type.ringsUntilDismissed) notification.flags = notification.flags or Notification.FLAG_INSISTENT
        val manager = context.getSystemService(NotificationManager::class.java)
        // Updating a showing notification does not run its full-screen intent again: with yesterday's alarm still
        // up, today's would only ring and leave the screen off. Posted anew, it stays the note's one notification.
        if (type.fullScreen) manager.cancel(NOTIFICATION_TAG, note.id.toInt())
        manager.notify(NOTIFICATION_TAG, note.id.toInt(), notification)
        if (launch == AlertLaunch.START_SCREEN) context.startActivity(alarmScreen(context, note, dueAt))
    }

    /** Intent the alarm screen and the notification actions send to [NoteAlertReceiver]. */
    fun actionIntent(context: Context, action: String, noteId: Long, dueAt: Long): PendingIntent =
        PendingIntent.getBroadcast(context, 0, receiverIntent(context, action, noteId, dueAt), PI_FLAGS)

    fun receiverIntent(context: Context, action: String, noteId: Long, dueAt: Long = 0): Intent =
        Intent(context, NoteAlertReceiver::class.java)
            .setAction(action)
            // One pending intent per note and action; extras alone do not tell pending intents apart
            .setData("jeznote://$action/$noteId".toUri())
            .putExtra(EXTRA_NOTE_ID, noteId)
            .putExtra(EXTRA_DUE_AT, dueAt)

    private fun arm(context: Context, note: Note, now: Long) {
        val schedule = note.schedule ?: return
        val next = schedule.nextAlert(now)
        if (next == null) {
            cancelAlarm(context, alertIntent(context, note.id, ACTION_ALERT))
            return
        }
        setAlarm(context, next.triggerAt, alertIntent(context, note.id, ACTION_ALERT, next.dueAt), schedule.alert == AlertType.ALARM)
    }

    /**
     * Wakes the app (inexactly, but also in Doze: at most once a day) when the first repeating note shows differently:
     * a done one comes back, a weekly one goes off its days or comes back on, so the watch follows.
     */
    private fun armPeriodRefresh(context: Context, notes: List<Note>, now: Long) {
        val next = notes.mapNotNull { it.nextShowChange(now) }.minOrNull()
        val operation = PendingIntent.getBroadcast(
            context, 0, Intent(context, NoteAlertReceiver::class.java).setAction(ACTION_PERIOD), PI_FLAGS
        )
        val manager = context.getSystemService(AlarmManager::class.java)
        if (next == null) manager.cancel(operation) else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, operation)
    }

    private fun setAlarm(context: Context, at: Long, operation: PendingIntent, alarm: Boolean) {
        val manager = context.getSystemService(AlarmManager::class.java)
        when {
            !canScheduleExact(context) -> manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
            // Alarm notes are real alarms: highest priority, and the system shows them as the next alarm
            alarm -> manager.setAlarmClock(AlarmManager.AlarmClockInfo(at, launcherIntent(context)), operation)
            else -> manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
        }
    }

    private fun cancelAlarm(context: Context, operation: PendingIntent) {
        context.getSystemService(AlarmManager::class.java).cancel(operation)
        operation.cancel()
    }

    private fun alertIntent(context: Context, noteId: Long, action: String, dueAt: Long = 0) =
        PendingIntent.getBroadcast(context, 0, receiverIntent(context, action, noteId, dueAt), PI_FLAGS)

    /**
     * The alarm screen on tap, and the Dismiss button. Also the full-screen intent, unless the screen is started directly:
     * the full-screen intent would still come up as a heads-up over it.
     */
    private fun NotificationCompat.Builder.openAlarmScreen(context: Context, note: Note, dueAt: Long, launch: AlertLaunch) = apply {
        val screen = PendingIntent.getActivity(context, 0, alarmScreen(context, note, dueAt), PI_FLAGS)
        if (launch == AlertLaunch.FULL_SCREEN_INTENT) setFullScreenIntent(screen, true)
        setContentIntent(screen)
        addAction(0, context.getString(R.string.note_alert_dismiss), actionIntent(context, ACTION_DISMISS, note.id, dueAt))
    }

    private fun alarmScreen(context: Context, note: Note, dueAt: Long): Intent =
        Intent(context, NoteAlarmActivity::class.java)
            .setData("jeznote://alarm/${note.id}".toUri())
            .putExtra(EXTRA_NOTE_ID, note.id)
            .putExtra(EXTRA_DUE_AT, dueAt)
            .putExtra(EXTRA_TEXT, note.text)
            .putExtra(EXTRA_ALERT, (note.schedule?.alert ?: AlertType.ALARM).name)
            // Not a user action: the launcher must not take it as leaving the notes screen
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)

    private fun launcherIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PI_FLAGS)

    private fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val reminders = NotificationChannel(
            CHANNEL_REMINDERS, context.getString(R.string.note_channel_reminders), NotificationManager.IMPORTANCE_HIGH
        ).apply { enableVibration(true) }
        val alarms = NotificationChannel(
            CHANNEL_ALARMS, context.getString(R.string.note_channel_alarms), NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setSound(
                Settings.System.DEFAULT_ALARM_ALERT_URI,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 600)
        }
        // The default notification sound, once; the full-screen intent wakes the screen
        val fullScreen = NotificationChannel(
            CHANNEL_FULL_SCREEN, context.getString(R.string.note_channel_full_screen), NotificationManager.IMPORTANCE_HIGH
        ).apply { enableVibration(true) }
        manager.createNotificationChannels(listOf(reminders, alarms, fullScreen))
    }
}
