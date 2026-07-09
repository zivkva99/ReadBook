package com.example.readbook.ui.home

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.readbook.data.AppDatabase
import com.example.readbook.data.BibleReadingProgress
import com.example.readbook.data.BibleReadingRepository
import com.example.readbook.data.BibleReadingStatus
import com.example.readbook.data.Clock
import com.example.readbook.data.DEFAULT_ENABLED_DAYS_MASK
import com.example.readbook.data.DEFAULT_TARGET_SECONDS
import com.example.readbook.data.DailyProgress
import com.example.readbook.data.ReadingConfig
import com.example.readbook.data.ReadingTimerRepository
import com.example.readbook.data.ScheduleEntry
import com.example.readbook.service.ReadingTimerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class FakeClock(var millis: Long = 0L) : Clock {
    override fun nowMillis(): Long = millis
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeViewModelTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val testSchedule = listOf(
        ScheduleEntry("יהושע", "י״ט", LocalDate.of(2026, 7, 5)),
        ScheduleEntry("יהושע", "כ׳", LocalDate.of(2026, 7, 6)),
    )

    // HomeViewModel uses viewModelScope (Dispatchers.Main.immediate) internally, and Room's
    // generated DAOs hop to their own internal executor by default — both must be pinned to
    // this test's own StandardTestDispatcher(testScheduler), or their work escapes the virtual
    // clock (same class of bug as the Service/BroadcastReceiver tests).
    private fun TestScope.buildViewModel(clock: Clock, today: LocalDate): Pair<HomeViewModel, AppDatabase> {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryCoroutineContext(StandardTestDispatcher(testScheduler))
            .build()
        val repository = ReadingTimerRepository(
            dailyProgressDao = db.dailyProgressDao(),
            readingSessionDao = db.readingSessionDao(),
            readingConfigDao = db.readingConfigDao(),
            statsDao = db.statsDao(),
            clock = clock,
        )
        val bibleReadingRepository = BibleReadingRepository(db.bibleReadingProgressDao(), testSchedule)
        val viewModel = HomeViewModel(
            dailyProgressDao = db.dailyProgressDao(),
            readingConfigDao = db.readingConfigDao(),
            repository = repository,
            bibleReadingRepository = bibleReadingRepository,
            clock = clock,
            today = { today },
        )
        testScheduler.runCurrent()
        return viewModel to db
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun uiState_reflectsNotConfigured_whenNoConfigSaved() = runTest {
        val (viewModel, db) = buildViewModel(FakeClock(), LocalDate.of(2026, 7, 5))

        assertEquals(HomeUiState.NotConfigured(notificationsDenied = false), viewModel.uiState.value)

        db.close()
    }

    @Test
    fun uiState_reflectsInProgress_onceConfigIsSavedForAnEnabledDay() = runTest {
        val (viewModel, db) = buildViewModel(FakeClock(), LocalDate.of(2026, 7, 5))
        db.readingConfigDao().upsert(ReadingConfig(enabledDaysMask = DEFAULT_ENABLED_DAYS_MASK, targetSeconds = DEFAULT_TARGET_SECONDS))
        testScheduler.runCurrent()

        val state = viewModel.uiState.value

        assertEquals(HomeUiState.InProgress(DEFAULT_TARGET_SECONDS, isRunning = false, notificationsDenied = false), state)

        db.close()
    }

    @Test
    fun setNotificationsDenied_updatesTheFlagOnTheCurrentState() = runTest {
        val (viewModel, db) = buildViewModel(FakeClock(), LocalDate.of(2026, 7, 5))

        viewModel.setNotificationsDenied(true)
        testScheduler.runCurrent()

        assertEquals(true, viewModel.uiState.value.notificationsDenied)

        db.close()
    }

    @Test
    fun onToggleTimer_whenNotRunning_startsTheService() = runTest {
        val (viewModel, db) = buildViewModel(FakeClock(), LocalDate.of(2026, 7, 5))
        db.readingConfigDao().upsert(ReadingConfig(enabledDaysMask = DEFAULT_ENABLED_DAYS_MASK, targetSeconds = DEFAULT_TARGET_SECONDS))
        testScheduler.runCurrent()

        viewModel.onToggleTimer(context)

        val started = shadowOf(context as Application).getNextStartedService()
        assertEquals(ReadingTimerService.ACTION_START, started?.action)

        db.close()
    }

    @Test
    fun onToggleTimer_whenRunning_stopsTheService() = runTest {
        val today = LocalDate.of(2026, 7, 5)
        val (viewModel, db) = buildViewModel(FakeClock(millis = 1000L), today)
        db.readingConfigDao().upsert(ReadingConfig(enabledDaysMask = DEFAULT_ENABLED_DAYS_MASK, targetSeconds = DEFAULT_TARGET_SECONDS))
        db.dailyProgressDao().upsert(
            DailyProgress(
                date = today.toString(), targetSeconds = 900, remainingSeconds = 900,
                completed = false, completedAt = null, activeSessionStartedAt = 1000L,
            )
        )
        testScheduler.runCurrent()

        viewModel.onToggleTimer(context)

        val started = shadowOf(context as Application).getNextStartedService()
        assertEquals(ReadingTimerService.ACTION_STOP, started?.action)

        db.close()
    }

    @Test
    fun onToggleTimer_whenNotConfigured_doesNothing() = runTest {
        val (viewModel, db) = buildViewModel(FakeClock(), LocalDate.of(2026, 7, 5))

        viewModel.onToggleTimer(context)

        assertNull(shadowOf(context as Application).getNextStartedService())

        db.close()
    }

    @Test
    fun onToggleTimer_onNonEnabledDayWithNoProgressYet_startsTheService() = runTest {
        val disabledDay = LocalDate.of(2026, 7, 10) // Friday, disabled by default
        val (viewModel, db) = buildViewModel(FakeClock(), disabledDay)
        db.readingConfigDao().upsert(ReadingConfig(enabledDaysMask = DEFAULT_ENABLED_DAYS_MASK, targetSeconds = DEFAULT_TARGET_SECONDS))
        testScheduler.runCurrent()

        viewModel.onToggleTimer(context)

        val started = shadowOf(context as Application).getNextStartedService()
        assertEquals(ReadingTimerService.ACTION_START, started?.action)

        db.close()
    }

    @Test
    fun onResetToday_restoresFullDurationOnAPausedDay() = runTest {
        val today = LocalDate.of(2026, 7, 5)
        val (viewModel, db) = buildViewModel(FakeClock(millis = 1000L), today)
        db.readingConfigDao().upsert(ReadingConfig(enabledDaysMask = DEFAULT_ENABLED_DAYS_MASK, targetSeconds = DEFAULT_TARGET_SECONDS))
        db.dailyProgressDao().upsert(
            DailyProgress(
                date = today.toString(), targetSeconds = 900, remainingSeconds = 500,
                completed = false, completedAt = null, activeSessionStartedAt = null,
            )
        )
        testScheduler.runCurrent()

        viewModel.onResetToday()
        testScheduler.runCurrent()

        val row = db.dailyProgressDao().getByDate(today.toString())
        assertEquals(DEFAULT_TARGET_SECONDS, row?.remainingSeconds)

        db.close()
    }

    @Test
    fun bibleReadingUiState_reflectsOnScheduleStatus() = runTest {
        val (viewModel, db) = buildViewModel(FakeClock(), LocalDate.of(2026, 7, 5))
        testScheduler.runCurrent()

        val state = viewModel.bibleReadingUiState.value

        assertEquals("יהושע י״ט", state.chapterText)
        assertEquals(true, state.buttonEnabled)

        db.close()
    }

    @Test
    fun onMarkChapterRead_advancesTheCursor_andUiStateReflectsIt() = runTest {
        val (viewModel, db) = buildViewModel(FakeClock(), LocalDate.of(2026, 7, 5))
        testScheduler.runCurrent()

        viewModel.onMarkChapterRead()
        testScheduler.runCurrent()

        // Cursor now points at schedule[1] (2026-7-6), but "today" is still 2026-7-5 - the
        // post-condition here is Waiting, not OnSchedule, so the button must be disabled.
        // Asserting only chapterText would pass even if buttonEnabled were wrongly left true.
        val state = viewModel.bibleReadingUiState.value
        assertEquals("כ׳", state.chapterText?.substringAfter(" "))
        assertEquals(false, state.buttonEnabled)

        db.close()
    }

    @Test
    fun onUndoMarkChapterRead_reversesTheCursorAdvance() = runTest {
        val (viewModel, db) = buildViewModel(FakeClock(), LocalDate.of(2026, 7, 5))
        testScheduler.runCurrent()
        viewModel.onMarkChapterRead()
        testScheduler.runCurrent()

        viewModel.onUndoMarkChapterRead()
        testScheduler.runCurrent()

        val state = viewModel.bibleReadingUiState.value
        assertEquals("י״ט", state.chapterText?.substringAfter(" "))
        assertEquals(true, state.buttonEnabled)

        db.close()
    }
}
