package com.example.readbook.scheduling

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.example.readbook.data.Clock
import com.example.readbook.data.ReadingConfig
import com.example.readbook.data.SystemClock
import com.example.readbook.data.isEnabledDay
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

    /** Schedules the daily rollover job for 00:01 the day after [from]. */
    fun scheduleRollover(from: LocalDate) {
        val nextMidnight = epochMillisAt(from.plusDays(1), hour = 0, minute = 1)
        alarmManager.setWindow(AlarmManager.RTC_WAKEUP, nextMidnight, WINDOW_LENGTH_MS, rolloverPendingIntent())
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

    companion object {
        val NUDGE_HOURS = listOf(9, 10, 11, 12, 13)
        const val WINDOW_LENGTH_MS = 15 * 60 * 1000L
        const val ROLLOVER_REQUEST_CODE = 100
        const val ACTION_NUDGE = "com.example.readbook.action.NUDGE"
        const val ACTION_ROLLOVER = "com.example.readbook.action.ROLLOVER"
    }
}
