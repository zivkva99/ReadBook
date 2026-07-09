package com.example.readbook.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppDatabaseMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate1To2_preservesExistingData_andAddsTheBibleReadingProgressTable() {
        // Seed a v1 database with real data, exactly as an already-installed app would have.
        helper.createDatabase(TEST_DB_NAME, 1).apply {
            execSQL("INSERT INTO reading_config (id, enabledDaysMask, targetSeconds) VALUES (0, 31, 900)")
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB_NAME, 2, true, AppDatabase.MIGRATION_1_2)

        migrated.query("SELECT enabledDaysMask FROM reading_config WHERE id = 0").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(31, cursor.getInt(0))
        }
        migrated.query("SELECT COUNT(*) FROM bible_reading_progress").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
    }

    companion object {
        private const val TEST_DB_NAME = "migration-test"
    }
}
