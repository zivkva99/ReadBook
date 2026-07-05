package com.example.readbook.ui.home

import com.example.readbook.data.DailyProgress
import com.example.readbook.data.DEFAULT_ENABLED_DAYS_MASK
import com.example.readbook.data.DEFAULT_TARGET_SECONDS
import com.example.readbook.data.ReadingConfig
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeUiStateTest {

    private val enabledDay = LocalDate.of(2026, 7, 5) // Sunday, enabled by default
    private val disabledDay = LocalDate.of(2026, 7, 10) // Friday, disabled by default
    private val config = ReadingConfig(enabledDaysMask = DEFAULT_ENABLED_DAYS_MASK, targetSeconds = DEFAULT_TARGET_SECONDS)

    @Test
    fun `no config yet yields NotConfigured`() {
        val state = deriveHomeUiState(
            config = null,
            progress = null,
            today = enabledDay,
            nowMillis = 0L,
            notificationsDenied = false,
        )

        assertEquals(HomeUiState.NotConfigured(notificationsDenied = false), state)
    }

    @Test
    fun `enabled day with no progress yet yields fresh InProgress, not running`() {
        val state = deriveHomeUiState(config, progress = null, today = enabledDay, nowMillis = 0L, notificationsDenied = false)

        assertEquals(HomeUiState.InProgress(DEFAULT_TARGET_SECONDS, isRunning = false, notificationsDenied = false), state)
    }

    @Test
    fun `non-enabled day with no progress yet yields NonEnabledDay`() {
        val state = deriveHomeUiState(config, progress = null, today = disabledDay, nowMillis = 0L, notificationsDenied = false)

        assertEquals(HomeUiState.NonEnabledDay(notificationsDenied = false), state)
    }

    @Test
    fun `completed progress yields Done, even on a non-enabled day`() {
        val progress = DailyProgress(
            date = disabledDay.toString(), targetSeconds = 900, remainingSeconds = 0,
            completed = true, completedAt = 1000L, activeSessionStartedAt = null,
        )

        val state = deriveHomeUiState(config, progress, today = disabledDay, nowMillis = 0L, notificationsDenied = false)

        assertEquals(HomeUiState.Done(notificationsDenied = false), state)
    }

    @Test
    fun `paused progress yields InProgress with the stored remaining time, not running`() {
        val progress = DailyProgress(
            date = enabledDay.toString(), targetSeconds = 900, remainingSeconds = 500,
            completed = false, completedAt = null, activeSessionStartedAt = null,
        )

        val state = deriveHomeUiState(config, progress, today = enabledDay, nowMillis = 999_999L, notificationsDenied = false)

        assertEquals(HomeUiState.InProgress(500, isRunning = false, notificationsDenied = false), state)
    }

    @Test
    fun `running progress yields a live-computed remaining time`() {
        val progress = DailyProgress(
            date = enabledDay.toString(), targetSeconds = 900, remainingSeconds = 500,
            completed = false, completedAt = null, activeSessionStartedAt = 1_000_000L,
        )

        // 30 seconds have elapsed since the session started.
        val state = deriveHomeUiState(config, progress, today = enabledDay, nowMillis = 1_030_000L, notificationsDenied = false)

        assertEquals(HomeUiState.InProgress(470, isRunning = true, notificationsDenied = false), state)
    }

    @Test
    fun `running progress clamps live remaining time at zero, never negative`() {
        val progress = DailyProgress(
            date = enabledDay.toString(), targetSeconds = 900, remainingSeconds = 10,
            completed = false, completedAt = null, activeSessionStartedAt = 1_000_000L,
        )

        // 500 seconds elapsed, way more than the 10 remaining.
        val state = deriveHomeUiState(config, progress, today = enabledDay, nowMillis = 1_500_000L, notificationsDenied = false)

        assertEquals(HomeUiState.InProgress(0, isRunning = true, notificationsDenied = false), state)
    }

    @Test
    fun `notificationsDenied flag passes through regardless of which state applies`() {
        assertEquals(true, (deriveHomeUiState(null, null, enabledDay, 0L, notificationsDenied = true) as HomeUiState.NotConfigured).notificationsDenied)
        assertEquals(true, (deriveHomeUiState(config, null, disabledDay, 0L, notificationsDenied = true) as HomeUiState.NonEnabledDay).notificationsDenied)
        assertEquals(true, (deriveHomeUiState(config, null, enabledDay, 0L, notificationsDenied = true) as HomeUiState.InProgress).notificationsDenied)
    }
}
