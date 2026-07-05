package com.example.readbook.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface DailyProgressDao {
    @Query("SELECT * FROM daily_progress WHERE date = :date")
    suspend fun getByDate(date: String): DailyProgress?

    @Upsert
    suspend fun upsert(progress: DailyProgress)

    @Query("DELETE FROM daily_progress WHERE date = :date")
    suspend fun deleteByDate(date: String)
}
