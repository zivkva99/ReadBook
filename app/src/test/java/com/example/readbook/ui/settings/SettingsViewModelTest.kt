package com.example.readbook.ui.settings

import android.app.AlarmManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.readbook.data.AppDatabase
import com.example.readbook.data.Clock
import com.example.readbook.data.DEFAULT_ENABLED_DAYS_MASK
import com.example.readbook.data.DEFAULT_TARGET_SECONDS
import com.example.readbook.data.ReadingConfig
import com.example.readbook.scheduling.NudgeScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class FakeClock(var millis: Long = 0L) : Clock {
    override fun nowMillis(): Long = millis
}

private fun epochMillisAt(date: LocalDate, hour: Int): Long =
    date.atTime(LocalTime.of(hour, 0)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsViewModelTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val enabledDay = LocalDate.of(2026, 7, 5) // Sunday, enabled by default

    private suspend fun TestScope.buildViewModel(config: ReadingConfig?, clock: Clock = FakeClock()): Pair<SettingsViewModel, AppDatabase> {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        shadowOf(alarmManager).getScheduledAlarms().forEach { it.operation?.let(alarmManager::cancel) }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryCoroutineContext(StandardTestDispatcher(testScheduler))
            .build()
        if (config != null) {
            db.readingConfigDao().upsert(config)
        }
        val viewModel = SettingsViewModel(
            readingConfigDao = db.readingConfigDao(),
            nudgeScheduler = NudgeScheduler(context, clock),
            today = { enabledDay },
        )
        testScheduler.runCurrent()
        return viewModel to db
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialState_reflectsTheSavedConfig() = runTest {
        val (viewModel, db) = buildViewModel(ReadingConfig(enabledDaysMask = DEFAULT_ENABLED_DAYS_MASK, targetSeconds = DEFAULT_TARGET_SECONDS))

        val state = viewModel.uiState.value
        assertEquals(setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY), state.selectedDays)
        assertEquals("15", state.durationMinutesText)
        assertEquals(null, state.durationErrorMessage)

        db.close()
    }

    @Test
    fun onDayToggled_addsADayThatWasNotSelected() = runTest {
        val (viewModel, db) = buildViewModel(ReadingConfig(enabledDaysMask = DEFAULT_ENABLED_DAYS_MASK, targetSeconds = DEFAULT_TARGET_SECONDS))

        viewModel.onDayToggled(DayOfWeek.FRIDAY)

        assertTrue(DayOfWeek.FRIDAY in viewModel.uiState.value.selectedDays)

        db.close()
    }

    @Test
    fun onDayToggled_removesADayThatWasSelected() = runTest {
        val (viewModel, db) = buildViewModel(ReadingConfig(enabledDaysMask = DEFAULT_ENABLED_DAYS_MASK, targetSeconds = DEFAULT_TARGET_SECONDS))

        viewModel.onDayToggled(DayOfWeek.SUNDAY)

        assertFalse(DayOfWeek.SUNDAY in viewModel.uiState.value.selectedDays)

        db.close()
    }

    @Test
    fun onDurationTextChanged_setsAnErrorMessage_forInvalidInput() = runTest {
        val (viewModel, db) = buildViewModel(ReadingConfig(enabledDaysMask = DEFAULT_ENABLED_DAYS_MASK, targetSeconds = DEFAULT_TARGET_SECONDS))

        viewModel.onDurationTextChanged("0")

        assertNotNull(viewModel.uiState.value.durationErrorMessage)

        db.close()
    }

    @Test
    fun onDurationTextChanged_clearsTheErrorMessage_forValidInput() = runTest {
        val (viewModel, db) = buildViewModel(ReadingConfig(enabledDaysMask = DEFAULT_ENABLED_DAYS_MASK, targetSeconds = DEFAULT_TARGET_SECONDS))
        viewModel.onDurationTextChanged("0")

        viewModel.onDurationTextChanged("20")

        assertEquals(null, viewModel.uiState.value.durationErrorMessage)

        db.close()
    }

    @Test
    fun onSave_withValidState_upsertsTheConfig_andReturnsTrue() = runTest {
        val (viewModel, db) = buildViewModel(ReadingConfig(enabledDaysMask = DEFAULT_ENABLED_DAYS_MASK, targetSeconds = DEFAULT_TARGET_SECONDS))
        viewModel.onDurationTextChanged("20")
        viewModel.onDayToggled(DayOfWeek.FRIDAY)

        val saved = viewModel.onSave()

        assertTrue(saved)
        val config = db.readingConfigDao().getConfig()
        assertEquals(20 * 60, config?.targetSeconds)
        assertTrue(config?.enabledDaysMask?.let { com.example.readbook.data.isEnabledDay(enabledDay.plusDays(5), it) } == true)

        db.close()
    }

    @Test
    fun onSave_withInvalidDuration_doesNotSave_andReturnsFalse() = runTest {
        val (viewModel, db) = buildViewModel(ReadingConfig(enabledDaysMask = DEFAULT_ENABLED_DAYS_MASK, targetSeconds = DEFAULT_TARGET_SECONDS))
        viewModel.onDurationTextChanged("not a number")

        val saved = viewModel.onSave()

        assertFalse(saved)
        assertEquals(DEFAULT_TARGET_SECONDS, db.readingConfigDao().getConfig()?.targetSeconds)

        db.close()
    }

    @Test
    fun onSave_whenTodayBecomesEnabled_schedulesTodaysNudges() = runTest {
        val clock = FakeClock(millis = epochMillisAt(enabledDay, hour = 6))
        // Start from a config where today (Sunday) is NOT enabled.
        val (viewModel, db) = buildViewModel(ReadingConfig(enabledDaysMask = 0, targetSeconds = DEFAULT_TARGET_SECONDS), clock)
        viewModel.onDayToggled(DayOfWeek.SUNDAY)

        viewModel.onSave()

        assertTrue(shadowOf(alarmManager).getScheduledAlarms().isNotEmpty())

        db.close()
    }

    @Test
    fun onSave_whenTodayBecomesDisabled_cancelsTodaysNudges() = runTest {
        val clock = FakeClock(millis = epochMillisAt(enabledDay, hour = 6))
        val (viewModel, db) = buildViewModel(ReadingConfig(enabledDaysMask = DEFAULT_ENABLED_DAYS_MASK, targetSeconds = DEFAULT_TARGET_SECONDS), clock)
        NudgeScheduler(context, clock).scheduleNudgesForToday(enabledDay, ReadingConfig(enabledDaysMask = DEFAULT_ENABLED_DAYS_MASK, targetSeconds = DEFAULT_TARGET_SECONDS))
        assertTrue(shadowOf(alarmManager).getScheduledAlarms().isNotEmpty())

        viewModel.onDayToggled(DayOfWeek.SUNDAY) // removes Sunday, today is no longer enabled

        viewModel.onSave()

        assertTrue(shadowOf(alarmManager).getScheduledAlarms().isEmpty())

        db.close()
    }
}
