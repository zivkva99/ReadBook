package com.example.readbook.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.readbook.data.ReadingConfigDao
import com.example.readbook.scheduling.NudgeScheduler

class SettingsViewModelFactory(
    private val readingConfigDao: ReadingConfigDao,
    private val nudgeScheduler: NudgeScheduler,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return SettingsViewModel(readingConfigDao, nudgeScheduler) as T
    }
}
