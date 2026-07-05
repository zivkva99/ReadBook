package com.example.readbook.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ReadingConfig::class, DailyProgress::class, ReadingSession::class, Stats::class],
    version = 1,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun readingConfigDao(): ReadingConfigDao
    abstract fun dailyProgressDao(): DailyProgressDao
    abstract fun readingSessionDao(): ReadingSessionDao
    abstract fun statsDao(): StatsDao
}
