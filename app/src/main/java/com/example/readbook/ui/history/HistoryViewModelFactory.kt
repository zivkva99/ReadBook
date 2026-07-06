package com.example.readbook.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.readbook.data.DailyProgressDao
import com.example.readbook.data.ReadingSessionDao
import com.example.readbook.data.StatsDao

class HistoryViewModelFactory(
    private val statsDao: StatsDao,
    private val dailyProgressDao: DailyProgressDao,
    private val readingSessionDao: ReadingSessionDao,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return HistoryViewModel(statsDao, dailyProgressDao, readingSessionDao) as T
    }
}
