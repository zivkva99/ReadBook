package com.example.readbook.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BibleReadingRepositoryTest {

    private val schedule = listOf(
        ScheduleEntry("א", "א׳", LocalDate.of(2026, 7, 5)),
        ScheduleEntry("א", "ב׳", LocalDate.of(2026, 7, 6)),
        ScheduleEntry("א", "ג׳", LocalDate.of(2026, 7, 7)),
    )

    private lateinit var db: AppDatabase
    private lateinit var repository: BibleReadingRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = BibleReadingRepository(db.bibleReadingProgressDao(), schedule)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun currentStatus_withNoProgressSavedYet_treatsCursorAsZero() = runTest {
        val status = repository.currentStatus(today = LocalDate.of(2026, 7, 5))

        assertEquals(BibleReadingStatus.OnSchedule(schedule[0]), status)
    }

    @Test
    fun markRead_advancesTheCursorByExactlyOne() = runTest {
        repository.markRead()

        val status = repository.currentStatus(today = LocalDate.of(2026, 7, 6))

        assertEquals(BibleReadingStatus.OnSchedule(schedule[1]), status)
    }

    @Test
    fun markRead_calledRepeatedly_advancesOneChapterAtATime() = runTest {
        repository.markRead()
        repository.markRead()

        val status = repository.currentStatus(today = LocalDate.of(2026, 7, 20))

        assertIs<BibleReadingStatus.Behind>(status)
        assertEquals(schedule[2], (status as BibleReadingStatus.Behind).entry)
    }

    @Test
    fun observeStatus_emitsTheCurrentStatus_andUpdatesAfterMarkRead() = runTest {
        val today = { LocalDate.of(2026, 7, 5) }

        val before = repository.observeStatus(today).first()
        repository.markRead()
        val after = repository.observeStatus(today).first()

        assertEquals(BibleReadingStatus.OnSchedule(schedule[0]), before)
        assertIs<BibleReadingStatus.Waiting>(after)
    }

    @Test
    fun undoMarkRead_reversesTheLastMarkRead() = runTest {
        repository.markRead()

        repository.undoMarkRead()

        val status = repository.currentStatus(today = LocalDate.of(2026, 7, 5))
        assertEquals(BibleReadingStatus.OnSchedule(schedule[0]), status)
    }

    @Test
    fun undoMarkRead_atCursorZero_isANoOp_neverGoesNegative() = runTest {
        repository.undoMarkRead()

        val status = repository.currentStatus(today = LocalDate.of(2026, 7, 5))
        assertEquals(BibleReadingStatus.OnSchedule(schedule[0]), status)
    }
}
