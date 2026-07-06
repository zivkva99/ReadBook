package com.example.readbook.ui.settings

import java.time.DayOfWeek

data class SettingsUiState(
    val selectedDays: Set<DayOfWeek>,
    val durationMinutesText: String,
    val durationErrorMessage: String?,
)

/** Null means invalid — a positive whole number of minutes is required. */
fun parseDurationMinutes(text: String): Int? {
    val minutes = text.trim().toIntOrNull() ?: return null
    return minutes.takeIf { it > 0 }
}
