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
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BibleReadingProgressDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: BibleReadingProgressDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.bibleReadingProgressDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun getProgress_returnsNull_whenNothingSaved() = runTest {
        assertNull(dao.getProgress())
    }

    @Test
    fun upsert_thenGetProgress_returnsSavedCursor() = runTest {
        dao.upsert(BibleReadingProgress(cursorIndex = 5))

        val result = dao.getProgress()

        assertEquals(5, result?.cursorIndex)
    }

    @Test
    fun upsert_overwritesExistingProgress_ratherThanInserting() = runTest {
        dao.upsert(BibleReadingProgress(cursorIndex = 5))
        dao.upsert(BibleReadingProgress(cursorIndex = 6))

        val result = dao.getProgress()

        assertEquals(6, result?.cursorIndex)
    }

    @Test
    fun observeProgress_emitsLatestValue_afterUpsert() = runTest {
        dao.upsert(BibleReadingProgress(cursorIndex = 3))

        val emitted = dao.observeProgress().first()

        assertEquals(3, emitted?.cursorIndex)
    }
}
