package com.example.readbook.scheduling

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.example.readbook.data.Clock
import com.example.readbook.data.ReadingConfig
import com.example.readbook.data.SystemClock
import com.example.readbook.data.isEnabledDay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Wraps AlarmManager for the two kinds of alarms this app needs. Always inexact
 * (setWindow, never setExactAndAllowWhileIdle) — a nudge firing a few minutes late costs
 * nothing, and exact alarms are denied by default on Android 14+, a permission fight this
 * feature doesn't need to have.
 */
class NudgeScheduler(
    private val context: Context,
    private val clock: Clock = SystemClock,
) {
    private val alarmManager: AlarmManager
        get() = context.getSystemService(AlarmManager::class.java)

    /** Schedules only the nudge hours still in the future; a no-op on a non-enabled day. */
    fun scheduleNudgesForToday(date: LocalDate, config: ReadingConfig) {
        if (!isEnabledDay(date, config.enabledDaysMask)) return
        for (hour in NUDGE_HOURS) {
            val triggerAt = epochMillisAt(date, hour)
            if (triggerAt <= clock.nowMillis()) continue
            alarmManager.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, WINDOW_LENGTH_MS, nudgePendingIntent(hour))
        }
    }

    fun cancelNudgesForToday() {
        for (hour in NUDGE_HOURS) {
            alarmManager.cancel(nudgePendingIntent(hour))
        }
    }

    /** Same hours/window/enabled-day rule as [scheduleNudgesForToday], targeting
     * [BibleReadingReminderReceiver] instead — a fully separate alarm family so the two
     * features' completion states never interfere with each other. */
    fun scheduleBibleReminderHoursForToday(date: LocalDate, config: ReadingConfig) {
        if (!isEnabledDay(date, config.enabledDaysMask)) return
        for (hour in NUDGE_HOURS) {
            val triggerAt = epochMillisAt(date, hour)
            if (triggerAt <= clock.nowMillis()) continue
            alarmManager.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, WINDOW_LENGTH_MS, bibleReminderPendingIntent(hour))
        }
    }

    fun cancelBibleReminderHoursForToday() {
        for (hour in NUDGE_HOURS) {
            alarmManager.cancel(bibleReminderPendingIntent(hour))
        }
    }

    /** Schedules the daily rollover job for 00:01 the day after [from]. */
    fun scheduleRollover(from: LocalDate) {
        val nextMidnight = epochMillisAt(from.plusDays(1), hour = 0, minute = 1)
        alarmManager.setWindow(AlarmManager.RTC_WAKEUP, nextMidnight, WINDOW_LENGTH_MS, rolloverPendingIntent())
    }

    /** Schedules exactly one extra nudge-check 15 minutes out — triggered by tapping "Snooze"
     * on a nudge notification. Reuses the normal ACTION_NUDGE path; when it fires it's a
     * completely ordinary NudgeReceiver invocation (same completion check, same notification). */
    fun scheduleSnooze() {
        val triggerAt = clock.nowMillis() + SNOOZE_DELAY_MS
        alarmManager.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, WINDOW_LENGTH_MS, snoozePendingIntent())
    }

    /** Schedules the weekly summary for the next Sunday 9:00 — [from] today if it's a Sunday
     * and still before 9:00, otherwise the following Sunday. */
    fun scheduleWeeklySummary(from: LocalDate) {
        var candidate = from
        while (candidate.dayOfWeek != DayOfWeek.SUNDAY) {
            candidate = candidate.plusDays(1)
        }
        var triggerAt = epochMillisAt(candidate, hour = 9)
        if (triggerAt <= clock.nowMillis()) {
            candidate = candidate.plusDays(7)
            triggerAt = epochMillisAt(candidate, hour = 9)
        }
        alarmManager.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, WINDOW_LENGTH_MS, weeklySummaryPendingIntent())
    }

    private fun epochMillisAt(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(LocalTime.of(hour, minute)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun nudgePendingIntent(hour: Int): PendingIntent {
        val intent = Intent(context, NudgeReceiver::class.java).setAction(ACTION_NUDGE)
        return PendingIntent.getBroadcast(
            context, hour, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun rolloverPendingIntent(): PendingIntent {
        val intent = Intent(context, RolloverReceiver::class.java).setAction(ACTION_ROLLOVER)
        return PendingIntent.getBroadcast(
            context, ROLLOVER_REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun snoozePendingIntent(): PendingIntent {
        val intent = Intent(context, NudgeReceiver::class.java).setAction(ACTION_NUDGE)
        return PendingIntent.getBroadcast(
            context, SNOOZE_REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun weeklySummaryPendingIntent(): PendingIntent {
        val intent = Intent(context, WeeklySummaryReceiver::class.java).setAction(ACTION_WEEKLY_SUMMARY)
        return PendingIntent.getBroadcast(
            context, WEEKLY_SUMMARY_REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun bibleReminderPendingIntent(hour: Int): PendingIntent {
        val intent = Intent(context, BibleReadingReminderReceiver::class.java).setAction(ACTION_BIBLE_REMINDER)
        return PendingIntent.getBroadcast(
            context, BIBLE_REMINDER_REQUEST_CODE_BASE + hour, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        val NUDGE_HOURS = listOf(9, 10, 11, 12, 13)
        const val WINDOW_LENGTH_MS = 15 * 60 * 1000L
        const val ROLLOVER_REQUEST_CODE = 100
        const val SNOOZE_REQUEST_CODE = 200
        const val SNOOZE_DELAY_MS = 15 * 60 * 1000L
        const val WEEKLY_SUMMARY_REQUEST_CODE = 400
        const val BIBLE_REMINDER_REQUEST_CODE_BASE = 500
        const val ACTION_NUDGE = "com.example.readbook.action.NUDGE"
        const val ACTION_ROLLOVER = "com.example.readbook.action.ROLLOVER"
        const val ACTION_WEEKLY_SUMMARY = "com.example.readbook.action.WEEKLY_SUMMARY"
        const val ACTION_BIBLE_REMINDER = "com.example.readbook.action.BIBLE_REMINDER"
    }
}
