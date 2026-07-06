package com.example.readbook.data

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun `daysToMask combines the default Sun-Thu set to the default mask`() {
        val days = setOf(
            DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
        )
        assertEquals(DEFAULT_ENABLED_DAYS_MASK, daysToMask(days))
    }

    @Test
    fun `daysToMask of an empty set is zero`() {
        assertEquals(0, daysToMask(emptySet()))
    }

    @Test
    fun `maskToDays is the exact inverse of daysToMask`() {
        val days = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)
        assertEquals(days, maskToDays(daysToMask(days)))
    }

    @Test
    fun `maskToDays of the default mask yields Sunday through Thursday`() {
        val days = maskToDays(DEFAULT_ENABLED_DAYS_MASK)
        assertEquals(
            setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY),
            days,
        )
    }
}
