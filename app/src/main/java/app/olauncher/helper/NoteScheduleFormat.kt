package app.olauncher.helper

import android.content.Context
import android.text.format.DateFormat
import app.olauncher.R
import app.olauncher.data.NoteSchedule
import app.olauncher.data.ScheduleType
import app.olauncher.data.dayBit
import app.olauncher.data.nextPeriodStart
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Short label shown next to a scheduled note: "daily 09:00", "Mon·Wed 18:00", "12 Oct 14:00". */
fun Context.scheduleLabel(schedule: NoteSchedule): String {
    val minute = schedule.minuteOfDay ?: 0
    return when (schedule.type) {
        ScheduleType.ONCE -> dateText(schedule.at ?: 0) + " " + timeText(schedule.at ?: 0)
        ScheduleType.DAILY -> getString(R.string.note_schedule_label_daily, minuteText(minute))
        ScheduleType.WEEKLY -> {
            val days = schedule.days ?: 0
            if (days and NoteSchedule.ALL_DAYS == NoteSchedule.ALL_DAYS)
                getString(R.string.note_schedule_label_daily, minuteText(minute))
            else
                weekDays().filter { days and dayBit(it) != 0 }.joinToString("·") { dayName(it) } + " " + minuteText(minute)
        }
    }
}

/** Label of a weekly note off its days at [now]: the next of its days and the time, "Mon 18:00". */
fun Context.nextDayLabel(schedule: NoteSchedule, now: Long): String {
    val next = schedule.nextPeriodStart(now) ?: return scheduleLabel(schedule)
    val day = Calendar.getInstance().apply { timeInMillis = next }.get(Calendar.DAY_OF_WEEK)
    return dayName(day) + " " + minuteText(schedule.minuteOfDay ?: 0)
}

/** Time of day in the user's 12/24 hour format. */
fun Context.timeText(time: Long): String = DateFormat.getTimeFormat(this).format(time)

/** [minuteOfDay] (minutes after midnight) as a time of day. */
fun Context.minuteText(minuteOfDay: Int): String = timeText(Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
    set(Calendar.MINUTE, minuteOfDay % 60)
}.timeInMillis)

/** Day and short month ("12 Oct"), with the year when it is not this year. */
fun dateText(time: Long): String {
    val locale = Locale.getDefault()
    val date = Calendar.getInstance().apply { timeInMillis = time }
    val skeleton = if (date.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR)) "dMMM" else "dMMMy"
    return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(time)
}

/** [Calendar.DAY_OF_WEEK] values starting at the locale's first day of the week. */
fun weekDays(): List<Int> {
    val first = Calendar.getInstance().firstDayOfWeek
    return (0..6).map { (first - 1 + it) % 7 + 1 }
}

/** Short day name of a [Calendar.DAY_OF_WEEK] value, e.g. "Mon". */
fun dayName(calendarDay: Int): String = DateFormatSymbols.getInstance().shortWeekdays[calendarDay]
