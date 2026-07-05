package com.example.readbook.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ReadingSessionDao {
    @Query("SELECT * FROM reading_session WHERE date = :date ORDER BY startedAt ASC")
    suspend fun getByDate(date: String): List<ReadingSession>

    @Insert
    suspend fun insert(session: ReadingSession)
}
