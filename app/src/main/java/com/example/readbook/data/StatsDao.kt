package com.example.readbook.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface StatsDao {
    @Query("SELECT * FROM stats WHERE id = 0")
    suspend fun getStats(): Stats?

    @Upsert
    suspend fun upsert(stats: Stats)
}
