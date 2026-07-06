package com.example.readbook.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.readbook.data.DailyProgressDao
import com.example.readbook.data.ReadingConfigDao
import com.example.readbook.data.ReadingTimerRepository

class HomeViewModelFactory(
    private val dailyProgressDao: DailyProgressDao,
    private val readingConfigDao: ReadingConfigDao,
    private val repository: ReadingTimerRepository,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return HomeViewModel(dailyProgressDao, readingConfigDao, repository) as T
    }
}
