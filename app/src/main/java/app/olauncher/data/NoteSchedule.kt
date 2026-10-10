package app.olauncher.data

import java.util.Calendar
import java.util.TimeZone

enum class ScheduleType { ONCE, DAILY, WEEKLY }

/**
 * How a note alerts: a notification that sounds once, a full-screen alert over the lock screen that sounds once, or an
 * alarm that opens full screen and rings until Done, Snooze or Dismiss. Stored by name, so new values leave old rows alone.
 */
enum class AlertType {
    NOTIFICATION, ALARM, FULL_SCREEN;

    /** Opens the alarm screen, also over the lock screen. */
    val fullScreen get() = this != NOTIFICATION

    /** Sound and vibration repeat until the alert goes; otherwise they come once. */
    val ringsUntilDismissed get() = this == ALARM

    /**
     * How the alert opens, given whether the screen is on ([interactive]) and the phone locked. While the phone is in
     * use Android turns a full-screen intent into a heads-up, so the alarm screen is started directly then. Only the
     * default launcher ([home]) may start it from the background; otherwise the heads-up stays.
     */
    fun launch(interactive: Boolean, locked: Boolean, home: Boolean) = when {
        !fullScreen -> AlertLaunch.NOTIFICATION
        interactive && !locked && home -> AlertLaunch.START_SCREEN
        else -> AlertLaunch.FULL_SCREEN_INTENT
    }
}

enum class AlertLaunch { NOTIFICATION, FULL_SCREEN_INTENT, START_SCREEN }

/**
 * When a note comes due and how it alerts. Daily and weekly notes repeat: a note counts as done for the current
 * period when it was struck after the period started, so a new period shows it active again without touching the row.
 * Times of day are local wall-clock times; they follow time zone and DST changes.
 */
data class NoteSchedule(
    val type: ScheduleType,
    /** DAILY and WEEKLY: local time of day in minutes, 0..1439. */
    val minuteOfDay: Int? = null,
    /** WEEKLY: selected days as a bit mask, Monday = bit 0 .. Sunday = bit 6, see [dayBit]. */
    val days: Int? = null,
    /** ONCE: the due time in epoch millis. */
    val at: Long? = null,
    val alert: AlertType = AlertType.NOTIFICATION,
    /** How many minutes before the due time the alert comes. */
    val leadMinutes: Int = DEFAULT_LEAD_MINUTES,
) {
    val repeats get() = type != ScheduleType.ONCE

    companion object {
        const val DEFAULT_LEAD_MINUTES = 30
        const val ALL_DAYS = 0b111_1111
    }
}

/** One alert of a schedule: the note is due at [dueAt] and the alert goes off at [triggerAt]. */
data class NoteAlertTime(val dueAt: Long, val triggerAt: Long)

/** The [NoteSchedule.days] bit of a [Calendar.DAY_OF_WEEK] value (Sunday = 1 .. Saturday = 7). */
fun dayBit(calendarDay: Int) = 1 shl ((calendarDay + 5) % 7)

/**
 * Start of the period [now] falls in: local midnight of the day, for a daily note and for a weekly one on one of its
 * days. A weekly period is that day alone: a day missed does not carry over. Null for a one-off note (it has no
 * periods), a weekly note without days and a weekly note off its days ([isOffDay]).
 */
fun NoteSchedule.periodStart(now: Long, zone: TimeZone = TimeZone.getDefault()): Long? {
    val mask = repeatDays() ?: return null
    val day = dayStart(now, 0, zone)
    return day.timeInMillis.takeIf { mask and dayBit(day.get(Calendar.DAY_OF_WEEK)) != 0 }
}

/** A weekly note on a day that is not one of its days: not due, so neither to do nor done, and off the home screen. */
fun NoteSchedule.isOffDay(time: Long, zone: TimeZone = TimeZone.getDefault()) =
    type == ScheduleType.WEEKLY && repeatDays() != null && periodStart(time, zone) == null

fun Note.isOffDay(time: Long, zone: TimeZone = TimeZone.getDefault()) = schedule?.isOffDay(time, zone) == true

/** When the period after the one [now] falls in starts: the next day of a repeating note, when it is due again. */
fun NoteSchedule.nextPeriodStart(now: Long, zone: TimeZone = TimeZone.getDefault()): Long? {
    val mask = repeatDays() ?: return null
    for (offset in 1..7) {
        val day = dayStart(now, offset, zone)
        if (mask and dayBit(day.get(Calendar.DAY_OF_WEEK)) != 0) return day.timeInMillis
    }
    return null
}

/**
 * The first alert that goes off after [now]. Completion is not looked at here: the alert checks it when it fires
 * ([shouldAlert]). Null when there is none, e.g. a one-off note whose alert time has passed.
 */
fun NoteSchedule.nextAlert(now: Long, zone: TimeZone = TimeZone.getDefault()): NoteAlertTime? {
    val lead = leadMinutes.coerceAtLeast(0) * MINUTE
    if (type == ScheduleType.ONCE) {
        val due = at ?: return null
        return NoteAlertTime(due, due - lead).takeIf { it.triggerAt > now }
    }
    val mask = repeatDays() ?: return null
    val minute = minuteOfDay ?: return null
    // A lead longer than a day puts the next alert's due time further out than a week
    for (offset in 0..7 + (lead / DAY).toInt() + 1) {
        val day = dayStart(now, offset, zone)
        if (mask and dayBit(day.get(Calendar.DAY_OF_WEEK)) == 0) continue
        val due = day.apply {
            set(Calendar.HOUR_OF_DAY, minute / 60)
            set(Calendar.MINUTE, minute % 60)
        }.timeInMillis
        if (due - lead > now) return NoteAlertTime(due, due - lead)
    }
    return null
}

/**
 * Whether the note counts as done at [time]: struck, and for a repeating note struck within that time's period. A
 * weekly note off its days is not done (nor to do).
 */
fun Note.isDoneAt(time: Long, zone: TimeZone = TimeZone.getDefault()): Boolean {
    val struck = strikedAt ?: return false
    val schedule = schedule ?: return true
    if (schedule.isOffDay(time, zone)) return false
    val start = schedule.periodStart(time, zone) ?: return true
    return struck >= start
}

/** The note as it shows at [now]: a repeating note struck in an earlier period comes back active (or off its day). */
fun Note.asOf(now: Long, zone: TimeZone = TimeZone.getDefault()): Note =
    if (isStruck && !isDoneAt(now, zone)) copy(strikedAt = null) else this

/**
 * Strikes the note or restores it, going by how it shows at [now] ([asOf]). Null for a weekly note off its day:
 * striking it then would mark the wrong day done.
 */
fun Note.toggledAt(now: Long, zone: TimeZone = TimeZone.getDefault()): Note? =
    if (isOffDay(now, zone)) null else asOf(now, zone).toggleStrike(now)

/** [orderNotes] over the notes as they show at [now]. */
fun displayNotes(notes: List<Note>, now: Long, zone: TimeZone = TimeZone.getDefault()) =
    orderNotes(notes.map { it.asOf(now, zone) })

/** The routine section's notes still to do at [now], in section order: what the home screen shows. */
fun homeNotes(notes: List<Note>, now: Long, zone: TimeZone = TimeZone.getDefault()) =
    displayNotes(notes, now, zone).filter { it.inSection && !it.isStruck && !it.isOffDay(now, zone) }

/**
 * When the note next shows differently by itself, at a local midnight: a done daily or weekly note comes back, a
 * weekly one goes off its days or comes back on. Null when nothing changes without the user (unscheduled, one-off,
 * daily and not done).
 */
fun Note.nextShowChange(now: Long, zone: TimeZone = TimeZone.getDefault()): Long? {
    if (schedule?.repeats != true) return null
    fun shown(time: Long) = isOffDay(time, zone) to isDoneAt(time, zone)
    val current = shown(now)
    return (1..8).map { dayStart(now, it, zone).timeInMillis }.firstOrNull { shown(it) != current }
}

/** An alert due at [dueAt] shows only while the note is still scheduled and not done for that due time's period. */
fun Note.shouldAlert(dueAt: Long, zone: TimeZone = TimeZone.getDefault()) =
    schedule != null && !isDoneAt(dueAt, zone)

/** Local midnight that starts the day after the day of [time]. */
fun nextDayStart(time: Long, zone: TimeZone = TimeZone.getDefault()) = dayStart(time, 1, zone).timeInMillis

private fun NoteSchedule.repeatDays(): Int? = when (type) {
    ScheduleType.ONCE -> null
    ScheduleType.DAILY -> NoteSchedule.ALL_DAYS
    ScheduleType.WEEKLY -> days?.and(NoteSchedule.ALL_DAYS)?.takeIf { it != 0 }
}

/** Local midnight of the day [offset] days from the day of [time]. */
private fun dayStart(time: Long, offset: Int, zone: TimeZone): Calendar =
    Calendar.getInstance(zone).apply {
        timeInMillis = time
        add(Calendar.DAY_OF_MONTH, offset)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

private const val MINUTE = 60_000L
private const val DAY = 24 * 60 * MINUTE
