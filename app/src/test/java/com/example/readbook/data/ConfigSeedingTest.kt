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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConfigSeedingTest {

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
    fun ensureConfigSeeded_withNothingSaved_seedsTheDefaults() = runTest {
        ensureConfigSeeded(dao)

        val config = dao.getConfig()
        assertEquals(DEFAULT_ENABLED_DAYS_MASK, config?.enabledDaysMask)
        assertEquals(DEFAULT_TARGET_SECONDS, config?.targetSeconds)
    }

    @Test
    fun ensureConfigSeeded_withExistingConfig_leavesItUntouched() = runTest {
        dao.upsert(ReadingConfig(enabledDaysMask = 0b1111111, targetSeconds = 600))

        ensureConfigSeeded(dao)

        val config = dao.getConfig()
        assertEquals(0b1111111, config?.enabledDaysMask)
        assertEquals(600, config?.targetSeconds)
    }
}
