package com.example.readbook.scheduling

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.readbook.data.AppDatabase
import com.example.readbook.data.DailyProgress
import com.example.readbook.notifications.TimerNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NudgeReceiverTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    // goAsync() only works when dispatched through the real broadcast mechanism (it reads a
    // PendingResult the framework sets up before onReceive runs) — calling receiver.onReceive()
    // directly leaves it null and NPEs. Register + sendBroadcast + idle the looper instead.
    private fun dispatch(receiver: NudgeReceiver, action: String) {
        context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        context.sendBroadcast(Intent(action))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun onReceive_todayNotCompleted_postsANudgeNotification() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryCoroutineContext(StandardTestDispatcher(testScheduler))
            .build()
        val receiver = NudgeReceiver()
        receiver.today = { LocalDate.of(2026, 7, 5) }
        receiver.dailyProgressDaoOverride = db.dailyProgressDao()
        receiver.scopeOverride = CoroutineScope(StandardTestDispatcher(testScheduler))
        // No DailyProgress row at all for today == not completed.

        dispatch(receiver, NudgeScheduler.ACTION_NUDGE)
        testScheduler.advanceUntilIdle()

        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = manager.activeNotifications.firstOrNull { it.id == NudgeReceiver.NOTIFICATION_ID_NUDGE }
        assertEquals(TimerNotifications.CHANNEL_NUDGE, notification?.notification?.channelId)

        db.close()
    }

    @Test
    fun onReceive_todayAlreadyCompleted_postsNothing() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryCoroutineContext(StandardTestDispatcher(testScheduler))
            .build()
        db.dailyProgressDao().upsert(
            DailyProgress(
                date = "2026-07-05",
                targetSeconds = 900,
                remainingSeconds = 0,
                completed = true,
                completedAt = 1000L,
                activeSessionStartedAt = null,
            )
        )
        val receiver = NudgeReceiver()
        receiver.today = { LocalDate.of(2026, 7, 5) }
        receiver.dailyProgressDaoOverride = db.dailyProgressDao()
        receiver.scopeOverride = CoroutineScope(StandardTestDispatcher(testScheduler))

        dispatch(receiver, NudgeScheduler.ACTION_NUDGE)
        testScheduler.advanceUntilIdle()

        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = manager.activeNotifications.firstOrNull { it.id == NudgeReceiver.NOTIFICATION_ID_NUDGE }
        assertNull(notification)

        db.close()
    }
}
