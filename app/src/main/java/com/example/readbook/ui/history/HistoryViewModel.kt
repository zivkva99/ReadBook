package com.example.readbook.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.readbook.data.DailyProgressDao
import com.example.readbook.data.ReadingSessionDao
import com.example.readbook.data.StatsDao
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class HistoryViewModel(
    statsDao: StatsDao,
    dailyProgressDao: DailyProgressDao,
    readingSessionDao: ReadingSessionDao,
    recentDaysLimit: Int = 30,
) : ViewModel() {

    val uiState: StateFlow<HistoryUiState> = combine(
        statsDao.observeStats(),
        dailyProgressDao.observeRecentDays(recentDaysLimit),
        readingSessionDao.observeAllSessions(),
    ) { stats, days, sessions ->
        deriveHistoryUiState(stats, days, sessions)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        HistoryUiState(currentStreak = 0, totalCompletedDays = 0, days = emptyList()),
    )
}
