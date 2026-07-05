package com.example.readbook.ui.home

import com.example.readbook.data.DailyProgress
import com.example.readbook.data.ReadingConfig
import com.example.readbook.data.isEnabledDay
import java.time.LocalDate

sealed interface HomeUiState {
    val notificationsDenied: Boolean

    data class NotConfigured(override val notificationsDenied: Boolean) : HomeUiState
    data class NonEnabledDay(override val notificationsDenied: Boolean) : HomeUiState
    data class InProgress(
        val remainingSeconds: Int,
        val isRunning: Boolean,
        override val notificationsDenied: Boolean,
    ) : HomeUiState
    data class Done(override val notificationsDenied: Boolean) : HomeUiState
}

/**
 * Once any [DailyProgress] row exists for today, it drives the state regardless of whether
 * today is an enabled day — matches "you can still manually start a session on a non-enabled
 * day; doing so creates today's row same as any other day," after which it behaves normally.
 * [NonEnabledDay] is only the entry-point shown before anything has been started yet.
 */
fun deriveHomeUiState(
    config: ReadingConfig?,
    progress: DailyProgress?,
    today: LocalDate,
    nowMillis: Long,
    notificationsDenied: Boolean,
): HomeUiState {
    if (config == null) return HomeUiState.NotConfigured(notificationsDenied)

    if (progress != null) {
        if (progress.completed) return HomeUiState.Done(notificationsDenied)
        val startedAt = progress.activeSessionStartedAt
        val remaining = if (startedAt != null) {
            val elapsedSeconds = ((nowMillis - startedAt) / 1000L).toInt()
            (progress.remainingSeconds - elapsedSeconds).coerceAtLeast(0)
        } else {
            progress.remainingSeconds
        }
        return HomeUiState.InProgress(remaining, isRunning = startedAt != null, notificationsDenied)
    }

    return if (isEnabledDay(today, config.enabledDaysMask)) {
        HomeUiState.InProgress(config.targetSeconds, isRunning = false, notificationsDenied)
    } else {
        HomeUiState.NonEnabledDay(notificationsDenied)
    }
}
