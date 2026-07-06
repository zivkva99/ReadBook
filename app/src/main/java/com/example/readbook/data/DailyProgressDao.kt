package com.example.readbook.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyProgressDao {
    @Query("SELECT * FROM daily_progress WHERE date = :date")
    suspend fun getByDate(date: String): DailyProgress?

    @Query("SELECT * FROM daily_progress WHERE date = :date")
    fun observeByDate(date: String): Flow<DailyProgress?>

    @Upsert
    suspend fun upsert(progress: DailyProgress)

    @Query("DELETE FROM daily_progress WHERE date = :date")
    suspend fun deleteByDate(date: String)

    @Query("SELECT * FROM daily_progress WHERE activeSessionStartedAt IS NOT NULL LIMIT 1")
    suspend fun getActiveSession(): DailyProgress?

    @Query("SELECT date FROM daily_progress WHERE completed = 1")
    suspend fun getCompletedDates(): List<String>

    @Query("SELECT * FROM daily_progress ORDER BY date DESC LIMIT :limit")
    fun observeRecentDays(limit: Int): Flow<List<DailyProgress>>
}
