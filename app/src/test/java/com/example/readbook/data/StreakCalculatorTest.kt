package com.example.readbook.data

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class StreakCalculatorTest {

    // 2026-07-05 is a Sunday.
    private val sunday = LocalDate.of(2026, 7, 5)

    @Test
    fun `single completed enabled day is a streak of 1`() {
        val streak = StreakCalculator.calculate(
            completedDates = setOf(sunday),
            enabledDaysMask = DEFAULT_ENABLED_DAYS_MASK, // Sun-Thu
            today = sunday,
        )

        assertEquals(1, streak)
    }

    @Test
    fun `consecutive completed enabled days count fully`() {
        val thursday = sunday.minusDays(3)
        val wednesday = sunday.minusDays(4)

        val streak = StreakCalculator.calculate(
            completedDates = setOf(sunday, thursday, wednesday),
            enabledDaysMask = DEFAULT_ENABLED_DAYS_MASK, // Sun-Thu
            today = sunday,
        )

        // Sun(today)=1, Sat skipped(not enabled), Fri skipped(not enabled), Thu=2, Wed=3
        assertEquals(3, streak)
    }

    @Test
    fun `a missed enabled day stops the streak there`() {
        val thursday = sunday.minusDays(3)
        // Wednesday is enabled by default but deliberately NOT in completedDates.

        val streak = StreakCalculator.calculate(
            completedDates = setOf(sunday, thursday),
            enabledDaysMask = DEFAULT_ENABLED_DAYS_MASK, // Sun-Thu
            today = sunday,
        )

        // Sun(today)=1, Sat skipped, Fri skipped, Thu=2, Wed enabled-but-missing -> stop
        assertEquals(2, streak)
    }

    @Test
    fun `non-enabled day completions never break or extend the streak`() {
        val saturday = sunday.minusDays(1) // not enabled by default, but manually completed
        val thursday = sunday.minusDays(3)

        val streak = StreakCalculator.calculate(
            completedDates = setOf(sunday, saturday, thursday),
            enabledDaysMask = DEFAULT_ENABLED_DAYS_MASK, // Sun-Thu
            today = sunday,
        )

        // Sun(today)=1, Sat is skipped regardless of being completed, Fri skipped, Thu=2
        assertEquals(2, streak)
    }

    @Test
    fun `today not completed yields a streak of 0`() {
        val streak = StreakCalculator.calculate(
            completedDates = emptySet(),
            enabledDaysMask = DEFAULT_ENABLED_DAYS_MASK,
            today = sunday,
        )

        assertEquals(0, streak)
    }

    @Test
    fun `zero enabled-days mask never loops forever and returns 0`() {
        val streak = StreakCalculator.calculate(
            completedDates = setOf(sunday),
            enabledDaysMask = 0,
            today = sunday,
        )

        assertEquals(0, streak)
    }
}
