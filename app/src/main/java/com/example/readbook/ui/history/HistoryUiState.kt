package com.example.readbook.ui.history

import com.example.readbook.data.DailyProgress
import com.example.readbook.data.ReadingSession
import com.example.readbook.data.Stats
import java.time.LocalDate

data class HistoryUiState(
    val currentStreak: Int,
    val totalCompletedDays: Int,
    val days: List<HistoryDayEntry>,
)

data class HistoryDayEntry(
    val date: LocalDate,
    val completed: Boolean,
    val sessions: List<HistorySessionEntry>,
)

data class HistorySessionEntry(
    val startedAt: Long,
    val secondsAdded: Int,
)

/** [currentStreak]/[totalCompletedDays] tracked as separate values — see the design doc's
 * softened-streak premise: a missed day never erases historical progress from view. */
fun deriveHistoryUiState(
    stats: Stats?,
    days: List<DailyProgress>,
    sessions: List<ReadingSession>,
): HistoryUiState {
    val sessionsByDate = sessions.groupBy { it.date }
    return HistoryUiState(
        currentStreak = stats?.currentStreak ?: 0,
        totalCompletedDays = stats?.totalCompletedDays ?: 0,
        days = days.map { day ->
            HistoryDayEntry(
                date = LocalDate.parse(day.date),
                completed = day.completed,
                sessions = sessionsByDate[day.date].orEmpty().map {
                    HistorySessionEntry(startedAt = it.startedAt, secondsAdded = it.secondsAdded)
                },
            )
        },
    )
}
