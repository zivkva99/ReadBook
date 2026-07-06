package com.example.readbook.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.readbook.data.DEFAULT_ENABLED_DAYS_MASK
import com.example.readbook.data.DEFAULT_TARGET_SECONDS
import com.example.readbook.data.ReadingConfig
import com.example.readbook.data.ReadingConfigDao
import com.example.readbook.data.daysToMask
import com.example.readbook.data.isEnabledDay
import com.example.readbook.data.maskToDays
import com.example.readbook.scheduling.NudgeScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate

class SettingsViewModel(
    private val readingConfigDao: ReadingConfigDao,
    private val nudgeScheduler: NudgeScheduler,
    private val today: () -> LocalDate = { LocalDate.now() },
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            selectedDays = maskToDays(DEFAULT_ENABLED_DAYS_MASK),
            durationMinutesText = (DEFAULT_TARGET_SECONDS / 60).toString(),
            durationErrorMessage = null,
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            readingConfigDao.getConfig()?.let { config ->
                _uiState.value = SettingsUiState(
                    selectedDays = maskToDays(config.enabledDaysMask),
                    durationMinutesText = (config.targetSeconds / 60).toString(),
                    durationErrorMessage = null,
                )
            }
        }
    }

    fun onDayToggled(day: DayOfWeek) {
        val current = _uiState.value
        val newDays = if (day in current.selectedDays) current.selectedDays - day else current.selectedDays + day
        _uiState.value = current.copy(selectedDays = newDays)
    }

    fun onDurationTextChanged(text: String) {
        val error = if (parseDurationMinutes(text) == null) {
            "Enter a whole number of minutes greater than 0"
        } else {
            null
        }
        _uiState.value = _uiState.value.copy(durationMinutesText = text, durationErrorMessage = error)
    }

    /** Returns false (without saving) if the current duration text is invalid. */
    suspend fun onSave(): Boolean {
        val state = _uiState.value
        val minutes = parseDurationMinutes(state.durationMinutesText) ?: return false

        val config = ReadingConfig(enabledDaysMask = daysToMask(state.selectedDays), targetSeconds = minutes * 60)
        readingConfigDao.upsert(config)

        // "Editing Settings immediately re-runs the scheduling logic for today and going forward."
        val todayDate = today()
        if (isEnabledDay(todayDate, config.enabledDaysMask)) {
            nudgeScheduler.scheduleNudgesForToday(todayDate, config)
        } else {
            nudgeScheduler.cancelNudgesForToday()
        }
        return true
    }
}
