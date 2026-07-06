package com.example.readbook.ui.history

import com.example.readbook.data.DailyProgress
import com.example.readbook.data.ReadingSession
import com.example.readbook.data.Stats
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryUiStateTest {

    private fun day(date: String, completed: Boolean) = DailyProgress(
        date = date, targetSeconds = 900, remainingSeconds = if (completed) 0 else 900,
        completed = completed, completedAt = if (completed) 1L else null, activeSessionStartedAt = null,
    )

    @Test
    fun `null stats yields zero streak and zero total`() {
        val state = deriveHistoryUiState(stats = null, days = emptyList(), sessions = emptyList())

        assertEquals(0, state.currentStreak)
        assertEquals(0, state.totalCompletedDays)
    }

    @Test
    fun `stats values pass through directly`() {
        val state = deriveHistoryUiState(
            stats = Stats(totalCompletedDays = 12, currentStreak = 4),
            days = emptyList(),
            sessions = emptyList(),
        )

        assertEquals(4, state.currentStreak)
        assertEquals(12, state.totalCompletedDays)
    }

    @Test
    fun `each day maps to an entry with its date and completed status, preserving order`() {
        val days = listOf(day("2026-07-05", completed = true), day("2026-07-04", completed = false))

        val state = deriveHistoryUiState(stats = null, days = days, sessions = emptyList())

        assertEquals(listOf(LocalDate.of(2026, 7, 5), LocalDate.of(2026, 7, 4)), state.days.map { it.date })
        assertEquals(listOf(true, false), state.days.map { it.completed })
    }

    @Test
    fun `sessions are grouped under the day matching their date`() {
        val days = listOf(day("2026-07-05", completed = true))
        val sessions = listOf(
            ReadingSession(date = "2026-07-05", startedAt = 1000L, endedAt = 1300L, secondsAdded = 300),
            ReadingSession(date = "2026-07-05", startedAt = 2000L, endedAt = 2600L, secondsAdded = 600),
        )

        val state = deriveHistoryUiState(stats = null, days = days, sessions = sessions)

        assertEquals(2, state.days.single().sessions.size)
        assertEquals(300, state.days.single().sessions[0].secondsAdded)
    }

    @Test
    fun `a day with no matching sessions gets an empty session list`() {
        val days = listOf(day("2026-07-05", completed = false))
        val sessions = listOf(ReadingSession(date = "2026-07-04", startedAt = 1000L, endedAt = 1300L, secondsAdded = 300))

        val state = deriveHistoryUiState(stats = null, days = days, sessions = sessions)

        assertTrue(state.days.single().sessions.isEmpty())
    }
}
