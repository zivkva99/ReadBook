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
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReadingSessionDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dailyProgressDao: DailyProgressDao
    private lateinit var sessionDao: ReadingSessionDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dailyProgressDao = db.dailyProgressDao()
        sessionDao = db.readingSessionDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insertDay(date: String) {
        dailyProgressDao.upsert(
            DailyProgress(
                date = date,
                targetSeconds = 900,
                remainingSeconds = 900,
                completed = false,
                completedAt = null,
                activeSessionStartedAt = null,
            )
        )
    }

    @Test
    fun getByDate_returnsEmptyList_whenNoSessions() = runTest {
        insertDay("2026-07-05")

        assertTrue(sessionDao.getByDate("2026-07-05").isEmpty())
    }

    @Test
    fun insert_thenGetByDate_returnsInsertedSessions() = runTest {
        insertDay("2026-07-05")
        sessionDao.insert(ReadingSession(date = "2026-07-05", startedAt = 1000L, endedAt = 1300L, secondsAdded = 300))
        sessionDao.insert(ReadingSession(date = "2026-07-05", startedAt = 2000L, endedAt = 2600L, secondsAdded = 600))

        val sessions = sessionDao.getByDate("2026-07-05")

        assertEquals(2, sessions.size)
        assertEquals(300, sessions[0].secondsAdded)
        assertEquals(600, sessions[1].secondsAdded)
    }

    @Test
    fun deletingDailyProgress_cascadesDeleteToItsSessions() = runTest {
        insertDay("2026-07-05")
        sessionDao.insert(ReadingSession(date = "2026-07-05", startedAt = 1000L, endedAt = 1300L, secondsAdded = 300))

        dailyProgressDao.deleteByDate("2026-07-05")

        assertTrue(sessionDao.getByDate("2026-07-05").isEmpty())
    }
}
