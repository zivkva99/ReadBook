package com.example.readbook.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeClock(var millis: Long = 0L) : Clock {
    override fun nowMillis(): Long = millis
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReadingTimerRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var clock: FakeClock
    private lateinit var repository: ReadingTimerRepository

    private val today = LocalDate.of(2026, 7, 5) // Sunday, an enabled day by default

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        clock = FakeClock(millis = 0L)
        repository = ReadingTimerRepository(
            dailyProgressDao = db.dailyProgressDao(),
            readingSessionDao = db.readingSessionDao(),
            readingConfigDao = db.readingConfigDao(),
            statsDao = db.statsDao(),
            clock = clock,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun start_onAFreshDay_createsRowWithFullTargetAndActiveSession() = runTest {
        clock.millis = 1_000_000L

        val row = repository.start(today)

        assertEquals(DEFAULT_TARGET_SECONDS, row.remainingSeconds)
        assertEquals(1_000_000L, row.activeSessionStartedAt)
        assertEquals(false, row.completed)
    }

    @Test
    fun start_onADayWithExistingPausedProgress_resumesFromRemainingSeconds() = runTest {
        clock.millis = 1_000_000L
        repository.start(today)
        clock.millis = 1_060_000L // 60s later
        repository.stop(today) // pauses with remainingSeconds = target - 60

        clock.millis = 2_000_000L
        val resumed = repository.start(today)

        assertEquals(DEFAULT_TARGET_SECONDS - 60, resumed.remainingSeconds)
        assertEquals(2_000_000L, resumed.activeSessionStartedAt)
    }

    @Test
    fun stop_reducesRemainingByElapsedTime_andClearsActiveSession() = runTest {
        clock.millis = 1_000_000L
        repository.start(today)
        clock.millis = 1_000_000L + 120_000L // 120s later

        val row = repository.stop(today)

        assertEquals(DEFAULT_TARGET_SECONDS - 120, row?.remainingSeconds)
        assertNull(row?.activeSessionStartedAt)
        assertEquals(false, row?.completed)
    }

    @Test
    fun stop_recordsAReadingSessionForTheElapsedSegment() = runTest {
        clock.millis = 1_000_000L
        repository.start(today)
        clock.millis = 1_000_000L + 120_000L

        repository.stop(today)

        val sessions = db.readingSessionDao().getByDate(today.toString())
        assertEquals(1, sessions.size)
        assertEquals(1_000_000L, sessions[0].startedAt)
        assertEquals(1_120_000L, sessions[0].endedAt)
        assertEquals(120, sessions[0].secondsAdded)
    }

    @Test
    fun stop_whenNoActiveSession_isANoOp() = runTest {
        clock.millis = 1_000_000L
        repository.start(today)
        clock.millis += 60_000L
        repository.stop(today)

        val before = db.dailyProgressDao().getByDate(today.toString())
        val result = repository.stop(today) // already stopped — second call
        val after = db.dailyProgressDao().getByDate(today.toString())

        assertEquals(before, result)
        assertEquals(before, after)
        assertTrue(db.readingSessionDao().getByDate(today.toString()).size == 1) // no extra session
    }

    @Test
    fun stop_whenElapsedReachesTarget_marksCompletedAndUpdatesStats() = runTest {
        clock.millis = 1_000_000L
        repository.start(today)
        clock.millis += DEFAULT_TARGET_SECONDS * 1000L // exactly the full target

        val row = repository.stop(today)

        assertEquals(0, row?.remainingSeconds)
        assertEquals(true, row?.completed)
        assertNotNull(row?.completedAt)

        val stats = db.statsDao().getStats()
        assertEquals(1, stats?.totalCompletedDays)
        assertEquals(1, stats?.currentStreak)
    }

    @Test
    fun stop_whenElapsedOvershootsTarget_clampsToZeroRatherThanGoingNegative() = runTest {
        clock.millis = 1_000_000L
        repository.start(today)
        clock.millis += (DEFAULT_TARGET_SECONDS + 500) * 1000L // way overshoots

        val row = repository.stop(today)

        assertEquals(0, row?.remainingSeconds)
        assertEquals(true, row?.completed)
    }

    @Test
    fun reconcileCrashedSession_returnsNull_whenNoDanglingSession() = runTest {
        assertNull(repository.reconcileCrashedSession())
    }

    @Test
    fun reconcileCrashedSession_finishesADanglingSessionFromAnEarlierDate() = runTest {
        val yesterday = today.minusDays(1)
        clock.millis = 1_000_000L
        repository.start(yesterday) // simulate a session that was never stopped (crash)
        clock.millis += 60_000L // detected some time later, on today's app launch

        val reconciled = repository.reconcileCrashedSession()

        assertEquals(yesterday.toString(), reconciled?.date)
        assertNull(reconciled?.activeSessionStartedAt)
        assertEquals(DEFAULT_TARGET_SECONDS - 60, reconciled?.remainingSeconds)
    }

    @Test
    fun start_whenTodayIsAlreadyCompleted_isANoOp() = runTest {
        clock.millis = 1_000_000L
        repository.start(today)
        clock.millis += DEFAULT_TARGET_SECONDS * 1000L
        val completed = repository.stop(today)
        assertEquals(true, completed?.completed)

        clock.millis += 500_000L
        val result = repository.start(today)

        assertEquals(completed, result)
        assertNull(db.dailyProgressDao().getByDate(today.toString())?.activeSessionStartedAt)
    }

    @Test
    fun resetToday_onAPausedInProgressDay_restoresFullDuration() = runTest {
        clock.millis = 1_000_000L
        repository.start(today)
        clock.millis += 120_000L
        repository.stop(today) // paused with remainingSeconds = target - 120

        val reset = repository.resetToday(today)

        assertEquals(DEFAULT_TARGET_SECONDS, reset?.remainingSeconds)
        assertNull(reset?.activeSessionStartedAt)
        assertEquals(false, reset?.completed)
    }

    @Test
    fun resetToday_whenNoRowExistsForToday_isANoOp() = runTest {
        val result = repository.resetToday(today)

        assertNull(result)
    }

    @Test
    fun resetToday_onACompletedDay_unCompletesAndRollsBackStats() = runTest {
        clock.millis = 1_000_000L
        repository.start(today)
        clock.millis += DEFAULT_TARGET_SECONDS * 1000L
        repository.stop(today) // completes today

        val statsAfterCompletion = db.statsDao().getStats()
        assertEquals(1, statsAfterCompletion?.totalCompletedDays)
        assertEquals(1, statsAfterCompletion?.currentStreak)

        val reset = repository.resetToday(today)

        assertEquals(false, reset?.completed)
        assertNull(reset?.completedAt)
        assertEquals(DEFAULT_TARGET_SECONDS, reset?.remainingSeconds)

        val statsAfterReset = db.statsDao().getStats()
        assertEquals(0, statsAfterReset?.totalCompletedDays)
        assertEquals(0, statsAfterReset?.currentStreak)
    }

    @Test
    fun resetToday_onACompletedDay_doesNotDeletePastReadingSessions() = runTest {
        clock.millis = 1_000_000L
        repository.start(today)
        clock.millis += DEFAULT_TARGET_SECONDS * 1000L
        repository.stop(today)

        repository.resetToday(today)

        assertEquals(1, db.readingSessionDao().getByDate(today.toString()).size)
    }
}
