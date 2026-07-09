package com.example.readbook

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.readbook.ui.history.HistoryScreen
import com.example.readbook.ui.history.HistoryViewModel
import com.example.readbook.ui.history.HistoryViewModelFactory
import com.example.readbook.ui.home.HomeScreen
import com.example.readbook.ui.home.HomeViewModel
import com.example.readbook.ui.home.HomeViewModelFactory
import com.example.readbook.ui.settings.SettingsScreen
import com.example.readbook.ui.settings.SettingsViewModel
import com.example.readbook.ui.settings.SettingsViewModelFactory
import com.example.readbook.ui.theme.ReadBookTheme
import kotlinx.coroutines.launch

private enum class Screen { HOME, SETTINGS, HISTORY }

class MainActivity : ComponentActivity() {

    private lateinit var homeViewModel: HomeViewModel

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        homeViewModel.setNotificationsDenied(!granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = (application as ReadingApp).container
        homeViewModel = ViewModelProvider(
            this,
            HomeViewModelFactory(
                container.dailyProgressDao,
                container.readingConfigDao,
                container.readingTimerRepository,
                container.bibleReadingRepository,
            ),
        )[HomeViewModel::class.java]

        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        homeViewModel.setNotificationsDenied(!granted)
        if (!granted) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            ReadBookTheme {
                var screen by remember { mutableStateOf(Screen.HOME) }

                when (screen) {
                    Screen.HOME -> {
                        val uiState by homeViewModel.uiState.collectAsStateWithLifecycle()
                        val bibleReadingUiState by homeViewModel.bibleReadingUiState.collectAsStateWithLifecycle()
                        HomeScreen(
                            uiState = uiState,
                            bibleReadingUiState = bibleReadingUiState,
                            onToggleTimer = { homeViewModel.onToggleTimer(this) },
                            onOpenSettings = { screen = Screen.SETTINGS },
                            onOpenHistory = { screen = Screen.HISTORY },
                            onResetToday = { homeViewModel.onResetToday() },
                            onMarkChapterRead = { homeViewModel.onMarkChapterRead() },
                            onUndoMarkChapterRead = { homeViewModel.onUndoMarkChapterRead() },
                        )
                    }
                    Screen.HISTORY -> {
                        val historyViewModel: HistoryViewModel = viewModel(
                            factory = HistoryViewModelFactory(
                                container.statsDao, container.dailyProgressDao, container.readingSessionDao,
                            ),
                        )
                        val historyState by historyViewModel.uiState.collectAsStateWithLifecycle()
                        HistoryScreen(uiState = historyState, onBack = { screen = Screen.HOME })
                    }
                    Screen.SETTINGS -> {
                        val settingsViewModel: SettingsViewModel = viewModel(
                            factory = SettingsViewModelFactory(container.readingConfigDao, container.nudgeScheduler),
                        )
                        val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
                        val scope = rememberCoroutineScope()
                        SettingsScreen(
                            uiState = settingsState,
                            onDayToggled = settingsViewModel::onDayToggled,
                            onDurationTextChanged = settingsViewModel::onDurationTextChanged,
                            onSave = {
                                scope.launch {
                                    if (settingsViewModel.onSave()) screen = Screen.HOME
                                }
                            },
                            onBack = { screen = Screen.HOME },
                        )
                    }
                }
            }
        }
    }
}
