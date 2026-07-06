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
import kotlin.test.assertTrue

private fun epochMillisAt(date: LocalDate, hour: Int): Long =
    date.atTime(LocalTime.of(hour, 0)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BootReceiverTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    // AlarmManager's shadow state has been observed leaking across test classes when the full
    // suite runs (order-dependent, not reproducible in isolation) — clear the slate defensively.
    @Before
    fun clearAnyPreExistingAlarms() {
        shadowOf(alarmManager).getScheduledAlarms().forEach { it.operation?.let(alarmManager::cancel) }
    }

    private fun dispatch(receiver: BootReceiver, action: String) {
        context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        context.sendBroadcast(Intent(action))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun onBootCompleted_reSchedulesTodaysNudges_andTheRolloverChain() = runTest {
        val today = LocalDate.of(2026, 7, 5) // Sunday, enabled by default
        val clock = FakeClock(millis = epochMillisAt(today, hour = 6))
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryCoroutineContext(StandardTestDispatcher(testScheduler))
            .build()
        db.readingConfigDao().upsert(ReadingConfig(enabledDaysMask = DEFAULT_ENABLED_DAYS_MASK, targetSeconds = DEFAULT_TARGET_SECONDS))
        val scheduler = NudgeScheduler(context, clock)
        val coordinator = NudgeSchedulingCoordinator(db.readingConfigDao(), scheduler)

        val receiver = BootReceiver()
        receiver.today = { today }
        receiver.coordinatorOverride = coordinator
        receiver.schedulerOverride = scheduler
        receiver.scopeOverride = CoroutineScope(StandardTestDispatcher(testScheduler))

        dispatch(receiver, Intent.ACTION_BOOT_COMPLETED)
        testScheduler.advanceUntilIdle()

        val alarms = shadowOf(alarmManager).getScheduledAlarms()
        // 5 nudge alarms (all still future at 6am) + 1 rollover alarm for tonight + 1 weekly
        // summary alarm (today is a Sunday in this test, and 6am is before the 9am slot).
        assertEquals(7, alarms.size)
    }

    @Test
    fun onReceive_ignoresIntentsThatAreNotBootCompleted() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryCoroutineContext(StandardTestDispatcher(testScheduler))
            .build()
        val scheduler = NudgeScheduler(context, FakeClock())
        val receiver = BootReceiver()
        receiver.coordinatorOverride = NudgeSchedulingCoordinator(db.readingConfigDao(), scheduler)
        receiver.schedulerOverride = scheduler
        receiver.scopeOverride = CoroutineScope(StandardTestDispatcher(testScheduler))

        dispatch(receiver, "some.other.action")
        testScheduler.advanceUntilIdle()

        assertTrue(shadowOf(alarmManager).getScheduledAlarms().isEmpty())

        db.close()
    }
}
