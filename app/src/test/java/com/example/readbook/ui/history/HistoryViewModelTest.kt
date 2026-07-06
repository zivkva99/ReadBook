package com.example.readbook.ui.history

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.readbook.data.AppDatabase
import com.example.readbook.data.DailyProgress
import com.example.readbook.data.ReadingSession
import com.example.readbook.data.Stats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HistoryViewModelTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun uiState_combinesStatsDaysAndSessions() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryCoroutineContext(StandardTestDispatcher(testScheduler))
            .build()
        db.statsDao().upsert(Stats(totalCompletedDays = 10, currentStreak = 3))
        db.dailyProgressDao().upsert(
            DailyProgress(
                date = "2026-07-05", targetSeconds = 900, remainingSeconds = 0,
                completed = true, completedAt = 1L, activeSessionStartedAt = null,
            )
        )
        db.readingSessionDao().insert(
            ReadingSession(date = "2026-07-05", startedAt = 1000L, endedAt = 1300L, secondsAdded = 300)
        )
        testScheduler.runCurrent()

        val viewModel = HistoryViewModel(db.statsDao(), db.dailyProgressDao(), db.readingSessionDao())
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals(3, state.currentStreak)
        assertEquals(10, state.totalCompletedDays)
        assertEquals(1, state.days.size)
        assertEquals(1, state.days.single().sessions.size)

        db.close()
    }

    @Test
    fun uiState_defaultsToZeroAndEmpty_whenNothingSaved() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryCoroutineContext(StandardTestDispatcher(testScheduler))
            .build()

        val viewModel = HistoryViewModel(db.statsDao(), db.dailyProgressDao(), db.readingSessionDao())
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals(0, state.currentStreak)
        assertEquals(0, state.totalCompletedDays)
        assertEquals(0, state.days.size)

        db.close()
    }
}
