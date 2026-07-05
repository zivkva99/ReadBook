package com.example.readbook.scheduling

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.readbook.data.AppDatabase
import com.example.readbook.data.DEFAULT_ENABLED_DAYS_MASK
import com.example.readbook.data.DEFAULT_TARGET_SECONDS
import com.example.readbook.data.ReadingConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals

private fun epochMillisAt(date: LocalDate, hour: Int, minute: Int = 0): Long =
    date.atTime(LocalTime.of(hour, minute)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RolloverReceiverTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    // AlarmManager's shadow state has been observed leaking across test classes when the full
    // suite runs (order-dependent, not reproducible in isolation) — clear the slate defensively.
    @Before
    fun clearAnyPreExistingAlarms() {
        shadowOf(alarmManager).getScheduledAlarms().forEach { it.operation?.let(alarmManager::cancel) }
    }

    private fun dispatch(receiver: RolloverReceiver, action: String) {
        context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        context.sendBroadcast(Intent(action))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun onReceive_schedulesTheNewDaysNudges_andReschedulesItself() = runTest {
        val newDay = LocalDate.of(2026, 7, 5) // Sunday, enabled by default
        val clock = FakeClock(millis = epochMillisAt(newDay, hour = 0, minute = 1))
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryCoroutineContext(StandardTestDispatcher(testScheduler))
            .build()
        db.readingConfigDao().upsert(ReadingConfig(enabledDaysMask = DEFAULT_ENABLED_DAYS_MASK, targetSeconds = DEFAULT_TARGET_SECONDS))
        val scheduler = NudgeScheduler(context, clock)
        val coordinator = NudgeSchedulingCoordinator(db.readingConfigDao(), scheduler)

        val receiver = RolloverReceiver()
        receiver.newDay = { newDay }
        receiver.coordinatorOverride = coordinator
        receiver.schedulerOverride = scheduler
        receiver.scopeOverride = CoroutineScope(StandardTestDispatcher(testScheduler))

        dispatch(receiver, NudgeScheduler.ACTION_ROLLOVER)
        testScheduler.advanceUntilIdle()

        val alarms = shadowOf(alarmManager).getScheduledAlarms()
        // 5 nudge alarms for the new day (all still in the future at 00:01) + 1 rollover alarm
        // for the following night.
        assertEquals(6, alarms.size)
        val rolloverAlarm = alarms.maxByOrNull { it.triggerAtTime }
        assertEquals(epochMillisAt(newDay.plusDays(1), hour = 0, minute = 1), rolloverAlarm?.triggerAtTime)

        db.close()
    }
}
