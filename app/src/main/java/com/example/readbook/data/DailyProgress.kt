package com.example.readbook.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A non-null [activeSessionStartedAt] with no matching Stop on next app launch means
 * the process died mid-session — that's the crash-recovery signal, not an error state.
 */
@Entity(tableName = "daily_progress")
data class DailyProgress(
    @PrimaryKey val date: String,
    val targetSeconds: Int,
    val remainingSeconds: Int,
    val completed: Boolean,
    val completedAt: Long?,
    val activeSessionStartedAt: Long?,
)
