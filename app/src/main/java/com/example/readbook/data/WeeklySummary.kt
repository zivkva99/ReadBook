package com.example.readbook.data

import java.time.LocalDate

data class WeeklySummary(val completedCount: Int, val enabledCount: Int)

/**
 * Walks the 7 days starting at [weekStart] (inclusive), counting how many are enabled per
 * [enabledDaysMask] and how many of those enabled days are in [completedDates].
 */
fun computeWeeklySummary(
    enabledDaysMask: Int,
    weekStart: LocalDate,
    completedDates: Set<LocalDate>,
): WeeklySummary {
    var enabledCount = 0
    var completedCount = 0
    for (offset in 0 until 7) {
        val date = weekStart.plusDays(offset.toLong())
        if (isEnabledDay(date, enabledDaysMask)) {
            enabledCount++
            if (date in completedDates) completedCount++
        }
    }
    return WeeklySummary(completedCount = completedCount, enabledCount = enabledCount)
}
