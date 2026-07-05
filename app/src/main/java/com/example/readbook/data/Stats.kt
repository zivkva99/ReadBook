package com.example.readbook.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "stats")
data class Stats(
    @PrimaryKey val id: Int = 0,
    val totalCompletedDays: Int,
    val currentStreak: Int,
)
