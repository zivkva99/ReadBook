package com.example.readbook.ui.home

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.readbook.data.Clock
import com.example.readbook.data.DailyProgressDao
import com.example.readbook.data.ReadingConfigDao
import com.example.readbook.data.SystemClock
import com.example.readbook.service.ReadingTimerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

class HomeViewModel(
    dailyProgressDao: DailyProgressDao,
    readingConfigDao: ReadingConfigDao,
    private val clock: Clock = SystemClock,
    private val today: () -> LocalDate = { LocalDate.now() },
) : ViewModel() {

    private val notificationsDenied = MutableStateFlow(false)

    fun setNotificationsDenied(denied: Boolean) {
        notificationsDenied.value = denied
    }

    val uiState: StateFlow<HomeUiState> = combine(
        readingConfigDao.observeConfig(),
        dailyProgressDao.observeByDate(today().toString()),
        notificationsDenied,
    ) { config, progress, denied ->
        deriveHomeUiState(config, progress, today(), clock.nowMillis(), denied)
    }.stateIn(
        viewModelScope,
        // Eagerly, not WhileSubscribed — this is a single-screen app with one subscriber and
        // no cost concern; Eagerly also means tests can read .value without needing to collect.
        SharingStarted.Eagerly,
        HomeUiState.NotConfigured(notificationsDenied = false),
    )

    /** No-op from [HomeUiState.NotConfigured] or [HomeUiState.Done] — nothing to toggle there. */
    fun onToggleTimer(context: Context) {
        val action = when (val state = uiState.value) {
            is HomeUiState.InProgress -> if (state.isRunning) {
                ReadingTimerService.ACTION_STOP
            } else {
                ReadingTimerService.ACTION_START
            }
            is HomeUiState.NonEnabledDay -> ReadingTimerService.ACTION_START
            is HomeUiState.NotConfigured, is HomeUiState.Done -> return
        }
        context.startService(Intent(context, ReadingTimerService::class.java).setAction(action))
    }
}
