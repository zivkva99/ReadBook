package com.example.readbook.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingConfigDao {
    @Query("SELECT * FROM reading_config WHERE id = 0")
    suspend fun getConfig(): ReadingConfig?

    @Query("SELECT * FROM reading_config WHERE id = 0")
    fun observeConfig(): Flow<ReadingConfig?>

    @Upsert
    suspend fun upsert(config: ReadingConfig)
}
