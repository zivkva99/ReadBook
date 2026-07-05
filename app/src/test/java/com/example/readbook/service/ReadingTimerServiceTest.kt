package com.example.readbook.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.readbook.data.AppDatabase
import com.example.readbook.data.Clock
import com.example.readbook.data.DEFAULT_TARGET_SECONDS
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
    // virtual clock. (UnconfinedTestDispatcher does NOT work here — Room's generated DAOs call
    // withContext() on a limitedParallelism() view of the dispatcher, and UnconfinedTestDispatcher's
    // dispatch() throws unless invoked via yield.) Must be built inside the test, since
    // testScheduler only exists within a TestScope.
    //
    // IMPORTANT: use runCurrent() to flush immediately-ready work (e.g. after Start/Stop), not
    // advanceUntilIdle() — the service's auto-complete job uses delay(), and advanceUntilIdle()
    // fast-forwards through ALL pending delays, including ones past whatever checkpoint you meant
    // to stop at — it jumps to the NEXT scheduled event, not to a time you specify. Use
    // advanceTimeBy(x) alone (it already runs anything scheduled within that window) when you need
    // to stop at a precise virtual time; don't follow it with advanceUntilIdle() unless you really
    // want to run everything remaining, however far out.
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
        testScheduler.runCurrent()

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
        testScheduler.runCurrent()
        clock.millis += 60_000L

        controller.withIntent(Intent(ReadingTimerService.ACTION_STOP)).startCommand(0, 2)
        testScheduler.runCurrent()

        val shadowService = shadowOf(service)
        assertTrue(shadowService.isForegroundStopped())
        assertTrue(shadowService.isStoppedBySelf())

        val row = db.dailyProgressDao().getByDate("2026-07-05")
        assertEquals(null, row?.activeSessionStartedAt)

        db.close()
    }

    @Test
    fun runningSession_autoCompletesWhenCountdownNaturallyElapses_withoutAnExplicitStop() = runTest {
        val clock = FakeClock(millis = 1_000_000L)
        val db = buildTestDb()
        val controller = Robolectric.buildService(ReadingTimerService::class.java).create()
        val service = controller.get()
        service.repository = db.repository(clock)
        service.scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        service.today = { LocalDate.of(2026, 7, 5) }

        controller.withIntent(Intent(ReadingTimerService.ACTION_START)).startCommand(0, 1)
        testScheduler.runCurrent()

        // Advance both the injected Clock and the coroutine scheduler's virtual time together,
        // past the full target duration — no ACTION_STOP is ever sent.
        val elapsedMs = (DEFAULT_TARGET_SECONDS + 1) * 1000L
        clock.millis += elapsedMs
        testScheduler.advanceTimeBy(elapsedMs)
        testScheduler.advanceUntilIdle()

        val row = db.dailyProgressDao().getByDate("2026-07-05")
        assertEquals(true, row?.completed)
        assertEquals(null, row?.activeSessionStartedAt)

        val shadowService = shadowOf(service)
        assertTrue(shadowService.isStoppedBySelf())
        assertTrue(shadowService.isForegroundStopped())

        db.close()
    }

    @Test
    fun manualStop_cancelsTheAutoCompleteJob_soItDoesNotFireLaterAndCutAFutureSessionShort() = runTest {
        val clock = FakeClock(millis = 1_000_000L)
        val db = buildTestDb()
        val controller = Robolectric.buildService(ReadingTimerService::class.java).create()
        val service = controller.get()
        service.repository = db.repository(clock)
        service.scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        service.today = { LocalDate.of(2026, 7, 5) }

        // Start #1 at virtual-time 0s — schedules a (soon-to-be-stale) auto-complete for
        // virtual-time 900s (the full DEFAULT_TARGET_SECONDS) if it were never cancelled.
        controller.withIntent(Intent(ReadingTimerService.ACTION_START)).startCommand(0, 1)
        testScheduler.runCurrent()

        // Stop after 10s — 890s remaining. The pending auto-complete job must be cancelled here.
        clock.millis += 10_000L
        controller.withIntent(Intent(ReadingTimerService.ACTION_STOP)).startCommand(0, 2)
        testScheduler.runCurrent()

        // Idle for a while (app closed) before resuming — advances virtual time without anything
        // firing, since the job was cancelled. This gap is what makes the next step distinguishing:
        // without it, the resumed session's own (shorter, 890s) completion would always land before
        // the original's (900s) stale fire point, and the test wouldn't prove the cancellation did
        // anything.
        val idleMs = 500_000L
        clock.millis += idleMs
        testScheduler.advanceTimeBy(idleMs)

        // Resume at virtual-time 500s — a fresh auto-complete for its own 890s remaining, which
        // will legitimately fire at virtual-time 500+890=1390s.
        controller.withIntent(Intent(ReadingTimerService.ACTION_START)).startCommand(0, 3)
        testScheduler.runCurrent()

        // Advance to virtual-time 900s — exactly where the STALE original job would have fired
        // (delay(900s) issued at virtual-time 0) — but well before the resumed job's own 1390s.
        // advanceTimeBy() alone (no trailing advanceUntilIdle()) stops precisely at this point
        // instead of jumping ahead to the next scheduled event (the resumed job's own 1390s).
        val elapsedMs = (DEFAULT_TARGET_SECONDS * 1000L) - idleMs
        clock.millis += elapsedMs
        testScheduler.advanceTimeBy(elapsedMs)

        val row = db.dailyProgressDao().getByDate("2026-07-05")
        assertEquals(false, row?.completed) // must NOT have been cut short by the stale job
        assertNotNull(row?.activeSessionStartedAt) // still running

        db.close()
    }
}
