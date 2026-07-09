package com.example.readbook.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Single-row table, same pattern as [ReadingConfig] — [cursorIndex] is the index of the next
 * unread chapter in the schedule parsed by [parseTanakhSchedule]. */
@Entity(tableName = "bible_reading_progress")
data class BibleReadingProgress(
    @PrimaryKey val id: Int = 0,
    val cursorIndex: Int = 0,
)
