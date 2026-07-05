package com.example.readbook.scheduling

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.readbook.data.DEFAULT_ENABLED_DAYS_MASK
import com.example.readbook.data.DEFAULT_TARGET_SECONDS
import com.example.readbook.data.ReadingConfig
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun epochMillisAt(date: LocalDate, hour: Int, minute: Int = 0): Long =
    date.atTime(LocalTime.of(hour, minute)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NudgeSchedulerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val clock = FakeClock()
    private val scheduler = NudgeScheduler(context, clock)

    private val enabledDay = LocalDate.of(2026, 7, 5) // Sunday, enabled by default
    private val disabledDay = LocalDate.of(2026, 7, 10) // Friday, disabled by default
    private val config = ReadingConfig(enabledDaysMask = DEFAULT_ENABLED_DAYS_MASK, targetSeconds = DEFAULT_TARGET_SECONDS)

    @Test
    fun scheduleNudgesForToday_onAnEnabledDay_schedulesAllFiveFutureHours() {
        clock.millis = epochMillisAt(enabledDay, hour = 6) // before any nudge hour

        scheduler.scheduleNudgesForToday(enabledDay, config)

        val alarms = shadowOf(alarmManager).getScheduledAlarms()
        assertEquals(5, alarms.size)
        val triggerTimes = alarms.map { it.triggerAtTime }.sorted()
        assertEquals(
            listOf(9, 10, 11, 12, 13).map { epochMillisAt(enabledDay, it) },
            triggerTimes,
        )
    }

    @Test
    fun scheduleNudgesForToday_skipsHoursAlreadyInThePast() {
        clock.millis = epochMillisAt(enabledDay, hour = 11, minute = 30)

        scheduler.scheduleNudgesForToday(enabledDay, config)

        val alarms = shadowOf(alarmManager).getScheduledAlarms()
        assertEquals(2, alarms.size) // only 12:00 and 13:00 remain
    }

    @Test
    fun scheduleNudgesForToday_onADisabledDay_schedulesNothing() {
        clock.millis = epochMillisAt(disabledDay, hour = 6)

        scheduler.scheduleNudgesForToday(disabledDay, config)

        assertTrue(shadowOf(alarmManager).getScheduledAlarms().isEmpty())
    }

    @Test
    fun cancelNudgesForToday_removesPreviouslyScheduledAlarms() {
        clock.millis = epochMillisAt(enabledDay, hour = 6)
        scheduler.scheduleNudgesForToday(enabledDay, config)
        assertEquals(5, shadowOf(alarmManager).getScheduledAlarms().size)

        scheduler.cancelNudgesForToday()

        assertTrue(shadowOf(alarmManager).getScheduledAlarms().isEmpty())
    }

    @Test
    fun scheduleRollover_schedulesExactlyOneAlarmAtNextMidnightOhOne() {
        scheduler.scheduleRollover(from = enabledDay)

        val alarms = shadowOf(alarmManager).getScheduledAlarms()
        assertEquals(1, alarms.size)
        assertEquals(epochMillisAt(enabledDay.plusDays(1), hour = 0, minute = 1), alarms[0].triggerAtTime)
    }
}
