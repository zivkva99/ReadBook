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
class ReadingConfigDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ReadingConfigDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.readingConfigDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun getConfig_returnsNull_whenNothingSaved() = runTest {
        assertNull(dao.getConfig())
    }

    @Test
    fun upsert_thenGetConfig_returnsSavedValues() = runTest {
        dao.upsert(ReadingConfig(enabledDaysMask = 0b0011111, targetSeconds = 900))

        val result = dao.getConfig()

        assertEquals(0b0011111, result?.enabledDaysMask)
        assertEquals(900, result?.targetSeconds)
    }

    @Test
    fun upsert_overwritesExistingConfig_ratherThanInserting() = runTest {
        dao.upsert(ReadingConfig(enabledDaysMask = 0b0011111, targetSeconds = 900))
        dao.upsert(ReadingConfig(enabledDaysMask = 0b1111111, targetSeconds = 600))

        val result = dao.getConfig()

        assertEquals(0b1111111, result?.enabledDaysMask)
        assertEquals(600, result?.targetSeconds)
    }
}
