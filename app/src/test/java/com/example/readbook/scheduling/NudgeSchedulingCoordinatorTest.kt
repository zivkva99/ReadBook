package com.example.readbook.scheduling

import android.app.AlarmManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.readbook.data.AppDatabase
import com.example.readbook.data.DEFAULT_ENABLED_DAYS_MASK
import com.example.readbook.data.DEFAULT_TARGET_SECONDS
import com.example.readbook.data.ReadingConfig
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertTrue

private fun epochMillisAt(date: LocalDate, hour: Int): Long =
    date.atTime(LocalTime.of(hour, 0)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NudgeSchedulingCoordinatorTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val clock = FakeClock()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val scheduler = NudgeScheduler(context, clock)
    private val coordinator = NudgeSchedulingCoordinator(db.readingConfigDao(), scheduler)

    private val enabledDay = LocalDate.of(2026, 7, 5) // Sunday

    // AlarmManager's shadow state has been observed leaking across test classes when the full
    // suite runs (order-dependent, not reproducible in isolation) — clear the slate defensively.
    @Before
    fun setUp() {
        clock.millis = epochMillisAt(enabledDay, hour = 6)
        shadowOf(alarmManager).getScheduledAlarms().forEach { it.operation?.let(alarmManager::cancel) }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun ensureScheduled_withSavedConfig_schedulesTodaysNudges() = runTest {
        db.readingConfigDao().upsert(ReadingConfig(enabledDaysMask = DEFAULT_ENABLED_DAYS_MASK, targetSeconds = DEFAULT_TARGET_SECONDS))

        coordinator.ensureScheduled(enabledDay)

        assertTrue(shadowOf(alarmManager).getScheduledAlarms().isNotEmpty())
    }

    @Test
    fun ensureScheduled_withNoConfigSavedYet_schedulesNothing_andDoesNotCrash() = runTest {
        coordinator.ensureScheduled(enabledDay)

        assertTrue(shadowOf(alarmManager).getScheduledAlarms().isEmpty())
    }

    @Test
    fun ensureBibleReminderScheduled_withSavedConfig_schedulesReminderHours() = runTest {
        db.readingConfigDao().upsert(ReadingConfig(enabledDaysMask = DEFAULT_ENABLED_DAYS_MASK, targetSeconds = DEFAULT_TARGET_SECONDS))

        coordinator.ensureBibleReminderScheduled(enabledDay)

        assertTrue(shadowOf(alarmManager).getScheduledAlarms().isNotEmpty())
    }

    @Test
    fun ensureBibleReminderScheduled_withNoConfigSavedYet_schedulesNothing_andDoesNotCrash() = runTest {
        coordinator.ensureBibleReminderScheduled(enabledDay)

        assertTrue(shadowOf(alarmManager).getScheduledAlarms().isEmpty())
    }
}
