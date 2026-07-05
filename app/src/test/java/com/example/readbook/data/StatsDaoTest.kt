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
class StatsDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: StatsDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.statsDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun getStats_returnsNull_whenNothingSaved() = runTest {
        assertNull(dao.getStats())
    }

    @Test
    fun upsert_thenGetStats_returnsSavedValues() = runTest {
        dao.upsert(Stats(totalCompletedDays = 5, currentStreak = 3))

        val result = dao.getStats()

        assertEquals(5, result?.totalCompletedDays)
        assertEquals(3, result?.currentStreak)
    }

    @Test
    fun upsert_overwritesExistingStats_ratherThanInserting() = runTest {
        dao.upsert(Stats(totalCompletedDays = 5, currentStreak = 3))
        dao.upsert(Stats(totalCompletedDays = 6, currentStreak = 0))

        val result = dao.getStats()

        assertEquals(6, result?.totalCompletedDays)
        assertEquals(0, result?.currentStreak)
    }
}
