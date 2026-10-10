package app.olauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class NoteScheduleTest {

    private val istanbul = TimeZone.getTimeZone("Europe/Istanbul")
    private val newYork = TimeZone.getTimeZone("America/New_York")
    private val berlin = TimeZone.getTimeZone("Europe/Berlin")

    /** Local wall-clock time in [zone]; months are 1-based. */
    private fun at(zone: TimeZone, year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0) =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, minute)
        }.timeInMillis

    private fun local(zone: TimeZone, time: Long) = Calendar.getInstance(zone).apply { timeInMillis = time }

    private fun daily(minuteOfDay: Int, lead: Int = 30) =
        NoteSchedule(ScheduleType.DAILY, minuteOfDay = minuteOfDay, leadMinutes = lead)

    private fun weekly(vararg calendarDays: Int, minuteOfDay: Int = 18 * 60, lead: Int = 30) =
        NoteSchedule(ScheduleType.WEEKLY, minuteOfDay = minuteOfDay, days = calendarDays.fold(0) { m, d -> m or dayBit(d) }, leadMinutes = lead)

    private fun once(at: Long, lead: Int = 30) = NoteSchedule(ScheduleType.ONCE, at = at, leadMinutes = lead)

    private fun note(id: Long, schedule: NoteSchedule?, strikedAt: Long? = null, position: Long = id) =
        Note(id = id, text = "n$id", createdAt = id, strikedAt = strikedAt, position = position, schedule = schedule)

    private val minute = 60_000L
    private val hour = 60 * minute

    @Test
    fun calendarFixtures() {
        // 2026-10-12 is a Monday, 2026-10-18 a Sunday
        assertEquals(Calendar.MONDAY, local(istanbul, at(istanbul, 2026, 10, 12)).get(Calendar.DAY_OF_WEEK))
        assertEquals(Calendar.SUNDAY, local(istanbul, at(istanbul, 2026, 10, 18)).get(Calendar.DAY_OF_WEEK))
    }

    @Test
    fun dayBitsRunMondayToSunday() {
        assertEquals(1, dayBit(Calendar.MONDAY))
        assertEquals(2, dayBit(Calendar.TUESDAY))
        assertEquals(32, dayBit(Calendar.SATURDAY))
        assertEquals(64, dayBit(Calendar.SUNDAY))
        assertEquals(NoteSchedule.ALL_DAYS, (Calendar.SUNDAY..Calendar.SATURDAY).fold(0) { m, d -> m or dayBit(d) })
    }

    // Periods

    @Test
    fun dailyPeriodStartsAtLocalMidnight() {
        val now = at(istanbul, 2026, 10, 12, 15, 30)
        assertEquals(at(istanbul, 2026, 10, 12), daily(9 * 60).periodStart(now, istanbul))
        assertEquals(at(istanbul, 2026, 10, 13), daily(9 * 60).nextPeriodStart(now, istanbul))
    }

    @Test
    fun weeklyPeriodIsTheSelectedDayAlone() {
        val monWed = weekly(Calendar.MONDAY, Calendar.WEDNESDAY)
        // Monday and Wednesday have their own period; Tuesday has none, it is off
        assertEquals(at(istanbul, 2026, 10, 12), monWed.periodStart(at(istanbul, 2026, 10, 12, 23, 59), istanbul))
        assertNull(monWed.periodStart(at(istanbul, 2026, 10, 13, 20), istanbul))
        assertTrue(monWed.isOffDay(at(istanbul, 2026, 10, 13, 20), istanbul))
        assertEquals(at(istanbul, 2026, 10, 14), monWed.periodStart(at(istanbul, 2026, 10, 14, 0, 1), istanbul))
        assertFalse(monWed.isOffDay(at(istanbul, 2026, 10, 14, 0, 1), istanbul))
        // Sunday is off; the next period is Monday
        assertNull(monWed.periodStart(at(istanbul, 2026, 10, 18, 23, 59), istanbul))
        assertEquals(at(istanbul, 2026, 10, 19), monWed.nextPeriodStart(at(istanbul, 2026, 10, 18, 23, 59), istanbul))
        // Daily, one-off and dayless weekly notes are never off
        assertFalse(daily(9 * 60).isOffDay(at(istanbul, 2026, 10, 13), istanbul))
        assertFalse(once(at(istanbul, 2026, 10, 12, 14)).isOffDay(at(istanbul, 2026, 10, 13), istanbul))
        assertFalse(NoteSchedule(ScheduleType.WEEKLY, minuteOfDay = 60, days = 0).isOffDay(at(istanbul, 2026, 10, 13), istanbul))
    }

    @Test
    fun oneOffAndDaylessWeeklyHaveNoPeriod() {
        assertNull(once(at(istanbul, 2026, 10, 12, 14)).periodStart(at(istanbul, 2026, 10, 12), istanbul))
        assertNull(NoteSchedule(ScheduleType.WEEKLY, minuteOfDay = 60, days = 0).periodStart(at(istanbul, 2026, 10, 12), istanbul))
    }

    @Test
    fun dailyNoteComesBackAtDayRollover() {
        val struck = note(1, daily(9 * 60), strikedAt = at(istanbul, 2026, 10, 12, 9, 5))
        assertTrue(struck.isDoneAt(at(istanbul, 2026, 10, 12, 23, 59), istanbul))
        assertTrue(struck.asOf(at(istanbul, 2026, 10, 12, 23, 59), istanbul).isStruck)
        val nextDay = at(istanbul, 2026, 10, 13)
        assertFalse(struck.isDoneAt(nextDay, istanbul))
        val shown = struck.asOf(nextDay, istanbul)
        assertFalse(shown.isStruck)
        // Only the view changes; the row keeps its schedule and position
        assertEquals(struck.copy(strikedAt = null), shown)
    }

    @Test
    fun weeklyNoteIsDoneForItsDayThenOffUntilItsNextDay() {
        val mondays = weekly(Calendar.MONDAY)
        val struck = note(1, mondays, strikedAt = at(istanbul, 2026, 10, 12, 18))
        // Done for the rest of Monday, then off (not struck) all week, then due again on the next Monday
        assertTrue(struck.isDoneAt(at(istanbul, 2026, 10, 12, 23, 59), istanbul))
        assertFalse(struck.isDoneAt(at(istanbul, 2026, 10, 13), istanbul))
        assertFalse(struck.asOf(at(istanbul, 2026, 10, 18, 23, 59), istanbul).isStruck)
        assertFalse(struck.isDoneAt(at(istanbul, 2026, 10, 19), istanbul))

        val monWed = note(2, weekly(Calendar.MONDAY, Calendar.WEDNESDAY), strikedAt = at(istanbul, 2026, 10, 12, 18))
        assertFalse(monWed.isDoneAt(at(istanbul, 2026, 10, 13, 12), istanbul))
        assertTrue(monWed.isOffDay(at(istanbul, 2026, 10, 13, 12), istanbul))
        assertFalse(monWed.isDoneAt(at(istanbul, 2026, 10, 14, 12), istanbul))
    }

    @Test
    fun oneOffNoteStaysStruck() {
        val struck = note(1, once(at(istanbul, 2026, 10, 12, 14)), strikedAt = at(istanbul, 2026, 10, 12, 13))
        assertTrue(struck.asOf(at(istanbul, 2026, 11, 1), istanbul).isStruck)
        val plain = note(2, null, strikedAt = at(istanbul, 2026, 1, 1))
        assertTrue(plain.asOf(at(istanbul, 2026, 11, 1), istanbul).isStruck)
    }

    @Test
    fun periodFollowsTheTimeZone() {
        // Struck at 22:00 in Istanbul; three hours later it is the next day there but still the same day in New York
        val strikedAt = at(istanbul, 2026, 10, 12, 22)
        val now = strikedAt + 3 * hour
        val struck = note(1, daily(9 * 60), strikedAt = strikedAt)
        assertFalse(struck.asOf(now, istanbul).isStruck)
        assertTrue(struck.asOf(now, newYork).isStruck)
    }

    @Test
    fun periodStartsAtMidnightOnDstDays() {
        // New York: 2026-03-08 springs forward at 02:00, 2026-11-01 falls back at 02:00
        val spring = daily(9 * 60).periodStart(at(newYork, 2026, 3, 8, 12), newYork)!!
        assertEquals(at(newYork, 2026, 3, 8), spring)
        assertEquals(0, local(newYork, spring).get(Calendar.HOUR_OF_DAY))
        // The DST day is 23 hours long, the fall-back day 25
        assertEquals(23 * hour, daily(0).nextPeriodStart(spring, newYork)!! - spring)
        val fall = daily(9 * 60).periodStart(at(newYork, 2026, 11, 1, 12), newYork)!!
        assertEquals(25 * hour, daily(0).nextPeriodStart(fall, newYork)!! - fall)

        val struckAfterMidnight = note(1, daily(9 * 60), strikedAt = fall + 30 * minute)
        assertTrue(struckAfterMidnight.isDoneAt(fall + 24 * hour, newYork))
        assertFalse(struckAfterMidnight.isDoneAt(fall + 25 * hour, newYork))
    }

    // Next alert

    @Test
    fun dailyAlertComesLeadMinutesBeforeTheTime() {
        val due = at(istanbul, 2026, 10, 12, 9)
        assertEquals(NoteAlertTime(due, due - 30 * minute), daily(9 * 60).nextAlert(at(istanbul, 2026, 10, 12, 8), istanbul))
        // Alert time passed: tomorrow's
        val tomorrow = at(istanbul, 2026, 10, 13, 9)
        assertEquals(NoteAlertTime(tomorrow, tomorrow - 30 * minute), daily(9 * 60).nextAlert(at(istanbul, 2026, 10, 12, 8, 45), istanbul))
        // Exactly at the alert time it is the next one (the one going off now is done)
        assertEquals(tomorrow, daily(9 * 60).nextAlert(due - 30 * minute, istanbul)!!.dueAt)
        assertEquals(NoteAlertTime(due, due), daily(9 * 60, lead = 0).nextAlert(at(istanbul, 2026, 10, 12, 8, 45), istanbul))
    }

    @Test
    fun leadCanReachIntoThePreviousDay() {
        val due = at(istanbul, 2026, 10, 13, 0, 10)
        val alert = daily(10).nextAlert(at(istanbul, 2026, 10, 12, 23), istanbul)
        assertEquals(NoteAlertTime(due, at(istanbul, 2026, 10, 12, 23, 40)), alert)
        val longLead = weekly(Calendar.WEDNESDAY, minuteOfDay = 60, lead = 120).nextAlert(at(istanbul, 2026, 10, 13, 22), istanbul)
        assertEquals(at(istanbul, 2026, 10, 14, 1), longLead!!.dueAt)
        assertEquals(at(istanbul, 2026, 10, 13, 23), longLead.triggerAt)
    }

    @Test
    fun weeklyAlertSkipsToTheNextSelectedDay() {
        val monWed = weekly(Calendar.MONDAY, Calendar.WEDNESDAY)
        // Monday after the alert: Wednesday
        assertEquals(at(istanbul, 2026, 10, 14, 18), monWed.nextAlert(at(istanbul, 2026, 10, 12, 19), istanbul)!!.dueAt)
        // Wednesday after the alert: next Monday
        assertEquals(at(istanbul, 2026, 10, 19, 18), monWed.nextAlert(at(istanbul, 2026, 10, 14, 17, 31), istanbul)!!.dueAt)
        // Sunday before Monday: tomorrow
        assertEquals(at(istanbul, 2026, 10, 19, 18), monWed.nextAlert(at(istanbul, 2026, 10, 18, 23), istanbul)!!.dueAt)
        // Only today selected and the time passed: a week later
        assertEquals(at(istanbul, 2026, 10, 19, 18), weekly(Calendar.MONDAY).nextAlert(at(istanbul, 2026, 10, 12, 20), istanbul)!!.dueAt)
        assertNull(NoteSchedule(ScheduleType.WEEKLY, minuteOfDay = 60, days = 0).nextAlert(at(istanbul, 2026, 10, 12), istanbul))
    }

    @Test
    fun oneOffAlertOnlyWhileAhead() {
        val due = at(istanbul, 2026, 10, 12, 14)
        assertEquals(NoteAlertTime(due, due - 30 * minute), once(due).nextAlert(at(istanbul, 2026, 10, 12, 13), istanbul))
        // The alert time passed (the due time has not): no alert, it is not moved
        assertNull(once(due).nextAlert(at(istanbul, 2026, 10, 12, 13, 45), istanbul))
        assertNull(once(due).nextAlert(at(istanbul, 2026, 10, 13), istanbul))
    }

    @Test
    fun dailyAlertKeepsLocalTimeAcrossDst() {
        // 09:00 every day stays 09:00 local, so the gap over a DST change is 23 or 25 hours
        val before = daily(9 * 60, lead = 0).nextAlert(at(newYork, 2026, 3, 7, 8), newYork)!!.dueAt
        val after = daily(9 * 60, lead = 0).nextAlert(before, newYork)!!.dueAt
        assertEquals(23 * hour, after - before)
        assertEquals(9, local(newYork, after).get(Calendar.HOUR_OF_DAY))

        val autumnBefore = daily(9 * 60, lead = 0).nextAlert(at(berlin, 2026, 10, 24, 8), berlin)!!.dueAt
        val autumnAfter = daily(9 * 60, lead = 0).nextAlert(autumnBefore, berlin)!!.dueAt
        assertEquals(25 * hour, autumnAfter - autumnBefore)
        assertEquals(9, local(berlin, autumnAfter).get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun timeInTheDstGapStillAlertsThatDay() {
        // 02:30 does not exist on 2026-03-08 in New York; the alert comes at 03:30, not a day later
        val due = daily(2 * 60 + 30, lead = 0).nextAlert(at(newYork, 2026, 3, 8, 1), newYork)!!.dueAt
        val shown = local(newYork, due)
        assertEquals(8, shown.get(Calendar.DAY_OF_MONTH))
        assertEquals(3, shown.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, shown.get(Calendar.MINUTE))
    }

    @Test
    fun repeatedTimeOnFallBackAlertsOnce() {
        // 01:30 happens twice on 2026-11-01 in New York; one alert that day, then the next day's
        val first = daily(90, lead = 0).nextAlert(at(newYork, 2026, 11, 1, 0), newYork)!!.dueAt
        assertEquals(1, local(newYork, first).get(Calendar.DAY_OF_MONTH))
        val next = daily(90, lead = 0).nextAlert(first, newYork)!!.dueAt
        assertEquals(2, local(newYork, next).get(Calendar.DAY_OF_MONTH))
        assertEquals(90, local(newYork, next).let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) })
    }

    @Test
    fun alertFollowsTheTimeZone() {
        val now = at(istanbul, 2026, 10, 12, 8)
        val inIstanbul = daily(9 * 60).nextAlert(now, istanbul)!!.dueAt
        val inNewYork = daily(9 * 60).nextAlert(now, newYork)!!.dueAt
        assertEquals(9, local(istanbul, inIstanbul).get(Calendar.HOUR_OF_DAY))
        assertEquals(9, local(newYork, inNewYork).get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun alertTypeDoesNotMoveTheAlert() {
        val now = at(istanbul, 2026, 10, 12, 8)
        val due = at(istanbul, 2026, 10, 12, 9)
        AlertType.entries.forEach { alert ->
            assertEquals(NoteAlertTime(due, due - 30 * minute), daily(9 * 60).copy(alert = alert).nextAlert(now, istanbul))
            assertTrue(note(1, once(due).copy(alert = alert)).shouldAlert(due, istanbul))
        }
    }

    // Alert types

    @Test
    fun onlyAlarmRingsUntilDismissedAndOnlyNotificationStaysOffScreen() {
        assertFalse(AlertType.NOTIFICATION.fullScreen)
        assertFalse(AlertType.NOTIFICATION.ringsUntilDismissed)
        assertTrue(AlertType.FULL_SCREEN.fullScreen)
        assertFalse(AlertType.FULL_SCREEN.ringsUntilDismissed)
        assertTrue(AlertType.ALARM.fullScreen)
        assertTrue(AlertType.ALARM.ringsUntilDismissed)
    }

    @Test
    fun storedAlertNamesStayTheSame() {
        // Room stores the enum by name; renaming one would break existing rows
        assertEquals(AlertType.NOTIFICATION, AlertType.valueOf("NOTIFICATION"))
        assertEquals(AlertType.ALARM, AlertType.valueOf("ALARM"))
        assertEquals(AlertType.FULL_SCREEN, AlertType.valueOf("FULL_SCREEN"))
    }

    // No alert when done

    @Test
    fun noAlertWhenDoneForThatPeriod() {
        val due = at(istanbul, 2026, 10, 12, 9)
        assertFalse(note(1, daily(9 * 60), strikedAt = at(istanbul, 2026, 10, 12, 7)).shouldAlert(due, istanbul))
        assertTrue(note(2, daily(9 * 60), strikedAt = at(istanbul, 2026, 10, 11, 9)).shouldAlert(due, istanbul))
        assertTrue(note(3, daily(9 * 60)).shouldAlert(due, istanbul))
        // Struck after the due time, before a snoozed alert comes: still done
        assertFalse(note(4, daily(9 * 60), strikedAt = due + 5 * minute).shouldAlert(due, istanbul))
        // An alert for tomorrow's due time that goes off tonight ignores today's strike
        assertTrue(note(5, daily(10), strikedAt = at(istanbul, 2026, 10, 12, 20)).shouldAlert(at(istanbul, 2026, 10, 13, 0, 10), istanbul))
    }

    @Test
    fun noAlertForDoneOneOffOrUnscheduledNote() {
        val due = at(istanbul, 2026, 10, 12, 14)
        assertFalse(note(1, once(due), strikedAt = due - hour).shouldAlert(due, istanbul))
        assertTrue(note(2, once(due)).shouldAlert(due, istanbul))
        assertFalse(note(3, null).shouldAlert(due, istanbul))
    }

    @Test
    fun weeklyAlertChecksItsOwnDay() {
        val monWed = weekly(Calendar.MONDAY, Calendar.WEDNESDAY)
        val doneMonday = note(1, monWed, strikedAt = at(istanbul, 2026, 10, 12, 18))
        assertFalse(doneMonday.shouldAlert(at(istanbul, 2026, 10, 12, 18), istanbul))
        assertTrue(doneMonday.shouldAlert(at(istanbul, 2026, 10, 14, 18), istanbul))
    }

    // Display and toggling

    @Test
    fun comebackNoteReturnsToItsSlotInTheSection() {
        val yesterday = at(istanbul, 2026, 10, 11, 20)
        val today = at(istanbul, 2026, 10, 12, 10)
        val notes = listOf(
            note(1, null, position = 0),
            note(2, daily(9 * 60), strikedAt = yesterday, position = 1),
            note(3, null, position = 2),
            note(4, daily(9 * 60), strikedAt = today, position = 3),
        )
        val now = at(istanbul, 2026, 10, 12, 12)
        // Both routines are in the section: 2 back active, 4 done today and struck in its slot
        val shown = displayNotes(notes, now, istanbul)
        assertEquals(listOf(2L, 4L, 1L, 3L), shown.map { it.id })
        assertEquals(listOf(false, true, false, false), shown.map { it.isStruck })
        // Reordering works on the shown list: an active routine moves past a done one
        val rows = noteRows(notes, now, istanbul)
        val moved = moveNoteRow(rows, 1, 2)!!
        assertEquals(listOf(4L, 2L, 1L, 3L), moved.filterIsInstance<NoteRow.Item>().map { it.note.id })
        assertEquals(mapOf(4L to 0L, 1L to 2L, 3L to 3L), reorderedNotes(moved).associate { it.id to it.position })
    }

    @Test
    fun toggleGoesByTheShownState() {
        val now = at(istanbul, 2026, 10, 12, 10)
        // Struck yesterday, shown active today: a toggle strikes it for today instead of clearing the old strike
        val restruck = note(1, daily(9 * 60), strikedAt = at(istanbul, 2026, 10, 11, 9)).asOf(now, istanbul).toggleStrike(now)
        assertEquals(now, restruck.strikedAt)
        val restored = note(2, daily(9 * 60), strikedAt = now - hour).asOf(now, istanbul).toggleStrike(now)
        assertNull(restored.strikedAt)
    }

    @Test
    fun shownStateFollowsThePeriod() {
        val now = at(istanbul, 2026, 10, 12, 10)
        val notes = listOf(
            note(1, daily(9 * 60), strikedAt = at(istanbul, 2026, 10, 11, 9)),
            note(2, daily(9 * 60), strikedAt = now - hour),
        )
        val struck = displayNotes(notes, now, istanbul).associate { it.id to it.isStruck }
        assertEquals(mapOf(1L to false, 2L to true), struck)
    }
}
