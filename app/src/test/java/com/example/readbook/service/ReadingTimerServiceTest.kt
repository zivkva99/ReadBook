package com.example.readbook.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.readbook.data.AppDatabase
import com.example.readbook.data.Clock
import com.example.readbook.data.ReadingTimerRepository
import com.example.readbook.notifications.TimerNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class FakeClock(var millis: Long = 0L) : Clock {
    override fun nowMillis(): Long = millis
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReadingTimerServiceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    // Room's suspend DAOs otherwise hop to their own internal executor thread, which races
    // ahead of kotlinx-coroutines-test's virtual clock. Pinning Room's query dispatcher to the
    // SAME StandardTestDispatcher(testScheduler) the test itself runs on keeps everything on one
    // virtual clock, drained deterministically by advanceUntilIdle(). (UnconfinedTestDispatcher
    // does NOT work here — Room's generated DAOs call withContext() on a limitedParallelism() view
    // of the dispatcher, and UnconfinedTestDispatcher's dispatch() throws unless invoked via yield.)
    // Must be built inside the test, since testScheduler only exists within a TestScope.
    private fun TestScope.buildTestDb(): AppDatabase =
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryCoroutineContext(StandardTestDispatcher(testScheduler))
            .build()

    private fun AppDatabase.repository(clock: Clock) = ReadingTimerRepository(
        dailyProgressDao = dailyProgressDao(),
        readingSessionDao = readingSessionDao(),
        readingConfigDao = readingConfigDao(),
        statsDao = statsDao(),
        clock = clock,
    )

    @Test
    fun onCreate_registersAllThreeNotificationChannels() {
        Robolectric.buildService(ReadingTimerService::class.java).create()

        val manager = context.getSystemService(NotificationManager::class.java)
        assertNotNull(manager.getNotificationChannel(TimerNotifications.CHANNEL_NUDGE))
        assertNotNull(manager.getNotificationChannel(TimerNotifications.CHANNEL_TIMER))
        assertNotNull(manager.getNotificationChannel(TimerNotifications.CHANNEL_COMPLETION))
    }

    @Test
    fun actionStart_startsForegroundWithTimerNotification_andStartsARepositorySession() = runTest {
        val clock = FakeClock(millis = 1_000_000L)
        val db = buildTestDb()
        val controller = Robolectric.buildService(ReadingTimerService::class.java).create()
        val service = controller.get()
        service.repository = db.repository(clock)
        service.scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        service.today = { LocalDate.of(2026, 7, 5) }

        controller.withIntent(Intent(ReadingTimerService.ACTION_START)).startCommand(0, 1)
        testScheduler.advanceUntilIdle()

        // NOTE: use explicit getter calls, not Kotlin property syntax, against ShadowService —
        // `.lastForegroundNotification` resolved incorrectly here even when the shadow's own
        // field was demonstrably set (confirmed via debug prints); `.getLastForegroundNotification()`
        // reads correctly. Suspect a Kotlin/Robolectric interop quirk with this shadow class.
        val shadowService = shadowOf(service)
        assertTrue(!shadowService.isForegroundStopped())
        val notification = shadowService.getLastForegroundNotification()
        assertNotNull(notification)
        assertEquals(TimerNotifications.CHANNEL_TIMER, notification.channelId)

        val row = db.dailyProgressDao().getByDate("2026-07-05")
        assertNotNull(row)
        assertEquals(1_000_000L, row.activeSessionStartedAt)

        db.close()
    }

    @Test
    fun actionStop_stopsForeground_andStopsTheRunningSession() = runTest {
        val clock = FakeClock(millis = 1_000_000L)
        val db = buildTestDb()
        val controller = Robolectric.buildService(ReadingTimerService::class.java).create()
        val service = controller.get()
        service.repository = db.repository(clock)
        service.scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        service.today = { LocalDate.of(2026, 7, 5) }

        controller.withIntent(Intent(ReadingTimerService.ACTION_START)).startCommand(0, 1)
        testScheduler.advanceUntilIdle()
        clock.millis += 60_000L

        controller.withIntent(Intent(ReadingTimerService.ACTION_STOP)).startCommand(0, 2)
        testScheduler.advanceUntilIdle()

        val shadowService = shadowOf(service)
        assertTrue(shadowService.isForegroundStopped())
        assertTrue(shadowService.isStoppedBySelf())

        val row = db.dailyProgressDao().getByDate("2026-07-05")
        assertEquals(null, row?.activeSessionStartedAt)

        db.close()
    }
}
