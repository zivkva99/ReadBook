package com.example.readbook.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Sun=1, Mon=2, Tue=4, Wed=8, Thu=16, Fri=32, Sat=64. Default: Sun-Thu = 0b0011111. */
const val DEFAULT_ENABLED_DAYS_MASK = 0b0011111
const val DEFAULT_TARGET_SECONDS = 900

@Entity(tableName = "reading_config")
data class ReadingConfig(
    @PrimaryKey val id: Int = 0,
    val enabledDaysMask: Int,
    val targetSeconds: Int,
)
