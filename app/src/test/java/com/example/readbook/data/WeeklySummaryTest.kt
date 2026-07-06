package com.example.readbook.data

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class WeeklySummaryTest {

    private val sunday = LocalDate.of(2026, 7, 5) // week start

    @Test
    fun `counts only enabled days in the denominator`() {
        val summary = computeWeeklySummary(
            enabledDaysMask = DEFAULT_ENABLED_DAYS_MASK, // Sun-Thu
            weekStart = sunday,
            completedDates = emptySet(),
        )

        assertEquals(5, summary.enabledCount)
        assertEquals(0, summary.completedCount)
    }

    @Test
    fun `counts completed enabled days in the numerator`() {
        val completed = setOf(sunday, sunday.plusDays(1), sunday.plusDays(4)) // Sun, Mon, Thu

        val summary = computeWeeklySummary(
            enabledDaysMask = DEFAULT_ENABLED_DAYS_MASK,
            weekStart = sunday,
            completedDates = completed,
        )

        assertEquals(3, summary.completedCount)
        assertEquals(5, summary.enabledCount)
    }

    @Test
    fun `a completed date outside the enabled mask does not count`() {
        val friday = sunday.plusDays(5) // disabled by default
        val summary = computeWeeklySummary(
            enabledDaysMask = DEFAULT_ENABLED_DAYS_MASK,
            weekStart = sunday,
            completedDates = setOf(friday),
        )

        assertEquals(0, summary.completedCount)
        assertEquals(5, summary.enabledCount)
    }

    @Test
    fun `a completed date outside the 7-day window does not count`() {
        val nextWeekSunday = sunday.plusDays(7)
        val summary = computeWeeklySummary(
            enabledDaysMask = DEFAULT_ENABLED_DAYS_MASK,
            weekStart = sunday,
            completedDates = setOf(nextWeekSunday),
        )

        assertEquals(0, summary.completedCount)
    }

    @Test
    fun `zero enabled days yields zero enabledCount`() {
        val summary = computeWeeklySummary(enabledDaysMask = 0, weekStart = sunday, completedDates = setOf(sunday))

        assertEquals(0, summary.enabledCount)
        assertEquals(0, summary.completedCount)
    }
}
