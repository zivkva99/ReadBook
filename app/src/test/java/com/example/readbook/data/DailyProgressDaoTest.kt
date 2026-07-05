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
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DailyProgressDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: DailyProgressDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.dailyProgressDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun getByDate_returnsNull_whenNoRowForThatDate() = runTest {
        assertNull(dao.getByDate("2026-07-05"))
    }

    @Test
    fun upsert_thenGetByDate_returnsSavedValues() = runTest {
        dao.upsert(
            DailyProgress(
                date = "2026-07-05",
                targetSeconds = 900,
                remainingSeconds = 900,
                completed = false,
                completedAt = null,
                activeSessionStartedAt = null,
            )
        )

        val result = dao.getByDate("2026-07-05")

        assertEquals("2026-07-05", result?.date)
        assertEquals(900, result?.targetSeconds)
        assertEquals(900, result?.remainingSeconds)
        assertEquals(false, result?.completed)
        assertNull(result?.completedAt)
        assertNull(result?.activeSessionStartedAt)
    }

    @Test
    fun upsert_updatesExistingRow_ratherThanInsertingDuplicate() = runTest {
        dao.upsert(
            DailyProgress(
                date = "2026-07-05",
                targetSeconds = 900,
                remainingSeconds = 900,
                completed = false,
                completedAt = null,
                activeSessionStartedAt = 1000L,
            )
        )
        dao.upsert(
            DailyProgress(
                date = "2026-07-05",
                targetSeconds = 900,
                remainingSeconds = 0,
                completed = true,
                completedAt = 2000L,
                activeSessionStartedAt = null,
            )
        )

        val result = dao.getByDate("2026-07-05")

        assertEquals(0, result?.remainingSeconds)
        assertEquals(true, result?.completed)
        assertEquals(2000L, result?.completedAt)
        assertNull(result?.activeSessionStartedAt)
    }
}
