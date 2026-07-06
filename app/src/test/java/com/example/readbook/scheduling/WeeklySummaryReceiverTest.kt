package com.example.readbook.scheduling

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.readbook.data.AppDatabase
import com.example.readbook.data.DEFAULT_ENABLED_DAYS_MASK
import com.example.readbook.data.DEFAULT_TARGET_SECONDS
import com.example.readbook.data.DailyProgress
import com.example.readbook.data.ReadingConfig
import com.example.readbook.notifications.TimerNotifications
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private fun epochMillisAt(date: LocalDate, hour: Int, minute: Int = 0): Long =
    date.atTime(LocalTime.of(hour, minute)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WeeklySummaryReceiverTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    // AlarmManager's shadow state has been observed leaking across test classes when the full
    // suite runs (order-dependent, not reproducible in isolation) — clear the slate defensively.
    @Before
    fun clearAnyPreExistingAlarms() {
        shadowOf(alarmManager).getScheduledAlarms().forEach { it.operation?.let(alarmManager::cancel) }
    }

    private fun dispatch(receiver: WeeklySummaryReceiver, action: String) {
        context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        context.sendBroadcast(Intent(action))
        shadowOf(Looper.getMainLooper()).idle()
    }

    // ReadingApp.onCreate()'s self-heal launches a real background coroutine (against the app's
    // real database, on Dispatchers.Default) the moment the test Application is created, and it
    // reliably wins its race against this test body — landing unrelated alarms on this same
    // shared AlarmManager before our final assertions run. It shares nothing with the code under
    // test here (different receivers, different request codes), so cancel those specific alarms
    // by reconstructing their identities (component + action + request code all match what
    // NudgeScheduler.scheduleRollover / scheduleNudgesForToday use) rather than blanket-clearing,
    // which would also wipe out this test's own just-scheduled alarm.
    //
    // Two kinds of noise can land here: the rollover alarm (request code 100) always scheduled by
    // the self-heal, and — depending on the real date/time the test happens to run at — up to
    // five nudge-hour alarms (request codes 9-13, one per NudgeScheduler.NUDGE_HOURS) scheduled
    // only when "today" (by real wall-clock time) is a default-enabled day and before that hour.
    // Cancel all of them so this test's alarm-count assertion is stable regardless of when it's
    // actually run.
    private fun cancelSelfHealSchedulingNoise() {
        val rolloverIntent = Intent(context, RolloverReceiver::class.java).setAction(NudgeScheduler.ACTION_ROLLOVER)
        val rolloverPendingIntent = PendingIntent.getBroadcast(
            context, NudgeScheduler.ROLLOVER_REQUEST_CODE, rolloverIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.cancel(rolloverPendingIntent)

        for (hour in NudgeScheduler.NUDGE_HOURS) {
            val nudgeIntent = Intent(context, NudgeReceiver::class.java).setAction(NudgeScheduler.ACTION_NUDGE)
            val nudgePendingIntent = PendingIntent.getBroadcast(
                context, hour, nudgeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarmManager.cancel(nudgePendingIntent)
        }
    }

    @Test
    fun onReceive_postsASummaryOfLastWeek_andReschedulesItself() = runTest {
        val sunday = LocalDate.of(2026, 7, 5)
        val clock = FakeClock(millis = epochMillisAt(sunday, hour = 9))
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryCoroutineContext(StandardTestDispatcher(testScheduler))
            .build()
        db.readingConfigDao().upsert(ReadingConfig(enabledDaysMask = DEFAULT_ENABLED_DAYS_MASK, targetSeconds = DEFAULT_TARGET_SECONDS))
        val lastSunday = sunday.minusDays(7)
        db.dailyProgressDao().upsert(
            DailyProgress(
                date = lastSunday.toString(), targetSeconds = 900, remainingSeconds = 0,
                completed = true, completedAt = 1L, activeSessionStartedAt = null,
            )
        )

        val receiver = WeeklySummaryReceiver()
        receiver.today = { sunday }
        receiver.dailyProgressDaoOverride = db.dailyProgressDao()
        receiver.readingConfigDaoOverride = db.readingConfigDao()
        receiver.schedulerOverride = NudgeScheduler(context, clock)
        receiver.scopeOverride = CoroutineScope(StandardTestDispatcher(testScheduler))

        dispatch(receiver, NudgeScheduler.ACTION_WEEKLY_SUMMARY)
        testScheduler.advanceUntilIdle()
        cancelSelfHealSchedulingNoise()

        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = manager.activeNotifications.firstOrNull { it.id == WeeklySummaryReceiver.NOTIFICATION_ID_WEEKLY_SUMMARY }
        assertNotNull(notification)
        assertEquals(TimerNotifications.CHANNEL_WEEKLY_SUMMARY, notification?.notification?.channelId)

        val alarms = shadowOf(alarmManager).getScheduledAlarms()
        assertEquals(1, alarms.size) // rescheduled for next Sunday

        db.close()
    }

    @Test
    fun onReceive_whenNoEnabledDays_postsNoNotification_butStillReschedules() = runTest {
        val sunday = LocalDate.of(2026, 7, 5)
        val clock = FakeClock(millis = epochMillisAt(sunday, hour = 9))
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryCoroutineContext(StandardTestDispatcher(testScheduler))
            .build()
        db.readingConfigDao().upsert(ReadingConfig(enabledDaysMask = 0, targetSeconds = DEFAULT_TARGET_SECONDS))

        val receiver = WeeklySummaryReceiver()
        receiver.today = { sunday }
        receiver.dailyProgressDaoOverride = db.dailyProgressDao()
        receiver.readingConfigDaoOverride = db.readingConfigDao()
        receiver.schedulerOverride = NudgeScheduler(context, clock)
        receiver.scopeOverride = CoroutineScope(StandardTestDispatcher(testScheduler))

        dispatch(receiver, NudgeScheduler.ACTION_WEEKLY_SUMMARY)
        testScheduler.advanceUntilIdle()
        cancelSelfHealSchedulingNoise()

        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = manager.activeNotifications.firstOrNull { it.id == WeeklySummaryReceiver.NOTIFICATION_ID_WEEKLY_SUMMARY }
        assertNull(notification)

        val alarms = shadowOf(alarmManager).getScheduledAlarms()
        assertEquals(1, alarms.size)

        db.close()
    }
}
