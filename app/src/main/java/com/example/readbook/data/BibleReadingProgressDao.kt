package com.example.readbook.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BibleReadingProgressDao {
    @Query("SELECT * FROM bible_reading_progress WHERE id = 0")
    suspend fun getProgress(): BibleReadingProgress?

    @Query("SELECT * FROM bible_reading_progress WHERE id = 0")
    fun observeProgress(): Flow<BibleReadingProgress?>

    @Upsert
    suspend fun upsert(progress: BibleReadingProgress)
}
