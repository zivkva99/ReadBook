package com.example.readbook.data

import java.time.LocalDate

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
            if (isEnabledDay(date, enabledDaysMask)) {
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
