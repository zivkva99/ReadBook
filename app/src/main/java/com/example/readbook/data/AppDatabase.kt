package com.example.readbook.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ReadingConfig::class, DailyProgress::class, ReadingSession::class, Stats::class,
        BibleReadingProgress::class,
    ],
    version = 2,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun readingConfigDao(): ReadingConfigDao
    abstract fun dailyProgressDao(): DailyProgressDao
    abstract fun readingSessionDao(): ReadingSessionDao
    abstract fun statsDao(): StatsDao
    abstract fun bibleReadingProgressDao(): BibleReadingProgressDao

    companion object {
        /** Adds the bible_reading_progress table — the app's first schema change. Never
         * fallbackToDestructiveMigration(); this project's reading history is the entire point. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bible_reading_progress` " +
                        "(`id` INTEGER NOT NULL, `cursorIndex` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
            }
        }
    }
}
