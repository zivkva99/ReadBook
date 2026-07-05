package com.example.readbook.data

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnabledDaysTest {

    // 2026-07-05 is a Sunday; +1=Mon, +5=Fri, +6=Sat.
    private val sunday = LocalDate.of(2026, 7, 5)

    @Test
    fun `default mask enables Sunday through Thursday`() {
        assertTrue(isEnabledDay(sunday, DEFAULT_ENABLED_DAYS_MASK))
        assertTrue(isEnabledDay(sunday.plusDays(4), DEFAULT_ENABLED_DAYS_MASK)) // Thu
    }

    @Test
    fun `default mask disables Friday and Saturday`() {
        assertFalse(isEnabledDay(sunday.plusDays(5), DEFAULT_ENABLED_DAYS_MASK)) // Fri
        assertFalse(isEnabledDay(sunday.plusDays(6), DEFAULT_ENABLED_DAYS_MASK)) // Sat
    }

    @Test
    fun `zero mask disables every day`() {
        assertFalse(isEnabledDay(sunday, 0))
    }
}
