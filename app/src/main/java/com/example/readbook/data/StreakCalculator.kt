package com.example.readbook.data

import java.time.DayOfWeek
import java.time.LocalDate

/** Sun=1, Mon=2, Tue=4, Wed=8, Thu=16, Fri=32, Sat=64 — matches [ReadingConfig.enabledDaysMask]. */
private fun bitFor(day: DayOfWeek): Int = when (day) {
    DayOfWeek.SUNDAY -> 0b0000001
    DayOfWeek.MONDAY -> 0b0000010
    DayOfWeek.TUESDAY -> 0b0000100
    DayOfWeek.WEDNESDAY -> 0b0001000
    DayOfWeek.THURSDAY -> 0b0010000
    DayOfWeek.FRIDAY -> 0b0100000
    DayOfWeek.SATURDAY -> 0b1000000
}

object StreakCalculator {

    private const val MAX_LOOKBACK_DAYS = 3650L // ~10 years — safety bound, not a real limit

    /**
     * Consecutive completed days walking back from [today], skipping days outside
     * [enabledDaysMask] entirely (they neither extend nor break the streak).
     */
    fun calculate(
        completedDates: Set<LocalDate>,
        enabledDaysMask: Int,
        today: LocalDate,
    ): Int {
        if (enabledDaysMask == 0) return 0

        var streak = 0
        var date = today
        var daysChecked = 0L
        while (daysChecked < MAX_LOOKBACK_DAYS) {
            val isEnabledDay = (enabledDaysMask and bitFor(date.dayOfWeek)) != 0
            if (isEnabledDay) {
                if (date in completedDates) {
                    streak++
                } else {
                    break
                }
            }
            date = date.minusDays(1)
            daysChecked++
        }
        return streak
    }
}
