package com.example.readbook.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface StatsDao {
    @Query("SELECT * FROM stats WHERE id = 0")
    suspend fun getStats(): Stats?

    @Query("SELECT * FROM stats WHERE id = 0")
    fun observeStats(): Flow<Stats?>

    @Upsert
    suspend fun upsert(stats: Stats)
}
