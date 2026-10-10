package app.olauncher.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import app.olauncher.R
import app.olauncher.data.AlertType
import app.olauncher.data.Note
import app.olauncher.data.NoteSchedule
import app.olauncher.data.ScheduleType
import app.olauncher.data.dayBit
import app.olauncher.databinding.DialogNoteScheduleBinding
import app.olauncher.helper.OlDialog
import app.olauncher.helper.createDialog
import app.olauncher.helper.dateText
import app.olauncher.helper.dayName
import app.olauncher.helper.dpToPx
import app.olauncher.helper.minuteText
import app.olauncher.helper.showPopupMenu
import app.olauncher.helper.weekDays
import java.util.Calendar

/**
 * Schedule menu of a note: once (date and time), daily (time) or weekly (days and time), notification, full screen or alarm, and
 * how long before the due time the alert comes. [onSave] gets the new schedule, or null when it is removed.
 */
class NoteScheduleDialog(
    private val fragment: Fragment,
    note: Note,
    private val onSave: (NoteSchedule?) -> Unit,
) {
    private val context = fragment.requireContext()
    private val scheduled = note.schedule != null
    private lateinit var binding: DialogNoteScheduleBinding

    private var type: ScheduleType
    private var minuteOfDay: Int
    private var days: Int
    /** Day of a one-off note; the time comes from [minuteOfDay]. */
    private val date = Calendar.getInstance()
    private var alert: AlertType
    private var lead: Int

    init {
        val now = Calendar.getInstance()
        val schedule = note.schedule
        type = schedule?.type ?: ScheduleType.DAILY
        schedule?.at?.let { date.timeInMillis = it }
        minuteOfDay = schedule?.minuteOfDay
            ?: schedule?.at?.let { date.get(Calendar.HOUR_OF_DAY) * 60 + date.get(Calendar.MINUTE) }
            // A new schedule starts at the next full hour, today when that is still ahead
            ?: ((now.get(Calendar.HOUR_OF_DAY) + 1) % 24 * 60).also { if (it == 0) date.add(Calendar.DAY_OF_MONTH, 1) }
        days = schedule?.days ?: dayBit(now.get(Calendar.DAY_OF_WEEK))
        alert = schedule?.alert ?: AlertType.NOTIFICATION
        lead = schedule?.leadMinutes ?: NoteSchedule.DEFAULT_LEAD_MINUTES
    }

    fun create(): OlDialog {
        val dialog = context.createDialog(
            title = R.string.note_schedule_title,
            action = R.string.note_schedule_save,
            neutral = if (scheduled) R.string.note_schedule_remove else 0,
            onNeutral = { onSave(null) },
            onAction = { onSave(build()) },
        ) { container ->
            binding = DialogNoteScheduleBinding.inflate(fragment.layoutInflater, container, false)
            binding.root
        }
        binding.scheduleOnce.setOnClickListener { type = ScheduleType.ONCE; render() }
        binding.scheduleDaily.setOnClickListener { type = ScheduleType.DAILY; render() }
        binding.scheduleWeekly.setOnClickListener { type = ScheduleType.WEEKLY; render() }
        binding.scheduleNotification.setOnClickListener { alert = AlertType.NOTIFICATION; render() }
        binding.scheduleFullScreen.setOnClickListener { alert = AlertType.FULL_SCREEN; render() }
        binding.scheduleAlarm.setOnClickListener { alert = AlertType.ALARM; render() }
        binding.scheduleDate.setOnClickListener { pickDate() }
        binding.scheduleTime.setOnClickListener { pickTime() }
        binding.scheduleLead.setOnClickListener { pickLead() }
        weekDays().forEach { day -> binding.scheduleDays.addView(dayToggle(day)) }
        render()
        return dialog
    }

    private fun dayToggle(day: Int) = TextView(context, null, 0, R.style.TextSmall).apply {
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        gravity = Gravity.CENTER
        setPadding(0, 12.dpToPx(), 0, 12.dpToPx())
        text = dayName(day)
        tag = day
        setOnClickListener {
            // At least one day stays picked
            val toggled = days xor dayBit(day)
            if (toggled != 0) days = toggled
            render()
        }
    }

    private fun render() {
        choose(binding.scheduleOnce, type == ScheduleType.ONCE)
        choose(binding.scheduleDaily, type == ScheduleType.DAILY)
        choose(binding.scheduleWeekly, type == ScheduleType.WEEKLY)
        binding.scheduleDays.isVisible = type == ScheduleType.WEEKLY
        for (i in 0 until binding.scheduleDays.childCount) {
            val view = binding.scheduleDays.getChildAt(i)
            choose(view as TextView, days and dayBit(view.tag as Int) != 0)
        }
        binding.scheduleDate.isVisible = type == ScheduleType.ONCE
        binding.scheduleDate.text = dateText(onceAt())
        binding.scheduleTime.text = context.minuteText(minuteOfDay)
        choose(binding.scheduleNotification, alert == AlertType.NOTIFICATION)
        choose(binding.scheduleFullScreen, alert == AlertType.FULL_SCREEN)
        choose(binding.scheduleAlarm, alert == AlertType.ALARM)
        binding.scheduleAlertHint.setText(
            when (alert) {
                AlertType.NOTIFICATION -> R.string.note_alert_notification_hint
                AlertType.FULL_SCREEN -> R.string.note_alert_full_screen_hint
                AlertType.ALARM -> R.string.note_alert_alarm_hint
            }
        )
        binding.scheduleLead.text = leadText(lead)
    }

    private fun choose(view: TextView, chosen: Boolean) {
        view.isSelected = chosen
        view.alpha = if (chosen) 1f else 0.35f
    }

    private fun pickDate() {
        DatePickerDialog(context, { _, year, month, day ->
            date.set(year, month, day)
            render()
        }, date.get(Calendar.YEAR), date.get(Calendar.MONTH), date.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun pickTime() {
        TimePickerDialog(context, { _, hour, minute ->
            minuteOfDay = hour * 60 + minute
            render()
        }, minuteOfDay / 60, minuteOfDay % 60, DateFormat.is24HourFormat(context)).show()
    }

    private fun pickLead() {
        binding.scheduleLead.showPopupMenu(configure = { menu ->
            LEADS.forEachIndexed { i, minutes -> menu.add(0, i, i, leadText(minutes)) }
        }) { item ->
            lead = LEADS[item.itemId]
            render()
        }
    }

    private fun leadText(minutes: Int) = when {
        minutes == 0 -> context.getString(R.string.note_lead_at_time)
        minutes % 60 == 0 -> context.getString(R.string.note_lead_hours, minutes / 60)
        else -> context.getString(R.string.note_lead_minutes, minutes)
    }

    private fun onceAt() = (date.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
        set(Calendar.MINUTE, minuteOfDay % 60)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun build() = when (type) {
        ScheduleType.ONCE -> NoteSchedule(type, at = onceAt(), alert = alert, leadMinutes = lead)
        ScheduleType.DAILY -> NoteSchedule(type, minuteOfDay = minuteOfDay, alert = alert, leadMinutes = lead)
        ScheduleType.WEEKLY -> NoteSchedule(type, minuteOfDay = minuteOfDay, days = days, alert = alert, leadMinutes = lead)
    }

    private companion object {
        val LEADS = listOf(0, 5, 10, 15, 30, 60, 120)
    }
}
