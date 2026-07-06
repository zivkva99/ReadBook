package com.example.readbook.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsUiStateTest {

    @Test
    fun `a positive integer string parses to minutes`() {
        assertEquals(15, parseDurationMinutes("15"))
    }

    @Test
    fun `zero is not a valid duration`() {
        assertNull(parseDurationMinutes("0"))
    }

    @Test
    fun `a negative number is not a valid duration`() {
        assertNull(parseDurationMinutes("-5"))
    }

    @Test
    fun `blank text is not a valid duration`() {
        assertNull(parseDurationMinutes(""))
    }

    @Test
    fun `non-numeric text is not a valid duration`() {
        assertNull(parseDurationMinutes("abc"))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertEquals(20, parseDurationMinutes("  20  "))
    }
}
