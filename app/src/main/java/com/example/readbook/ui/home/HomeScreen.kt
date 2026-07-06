package com.example.readbook.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onToggleTimer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ReadBook") },
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "History")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            if (uiState.notificationsDenied) {
                NotificationsOffBanner()
            }
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                when (uiState) {
                    is HomeUiState.NotConfigured -> Text("Setting up…")
                    is HomeUiState.NonEnabledDay -> NonEnabledDayContent(onToggleTimer)
                    is HomeUiState.Done -> Text("Nice — today's reading is done")
                    is HomeUiState.InProgress -> InProgressContent(uiState, onToggleTimer)
                }
            }
        }
    }
}

@Composable
private fun NotificationsOffBanner() {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Text(
            "Notifications are off — nudges won't fire",
            modifier = Modifier.padding(12.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun NonEnabledDayContent(onToggleTimer: () -> Unit) {
    Text("Today isn't a scheduled reading day.")
    Text("Want to read anyway?")
    Button(onClick = onToggleTimer, modifier = Modifier.padding(top = 16.dp)) {
        Text("Start")
    }
}

@Composable
private fun InProgressContent(state: HomeUiState.InProgress, onToggleTimer: () -> Unit) {
    // Live 1Hz countdown while running — the ViewModel/DB only update on Start/Stop/Completion,
    // not every second, so the visual tick lives here and resets whenever the underlying state
    // (a new baseline remainingSeconds, or isRunning flipping) actually changes.
    var displaySeconds by remember(state) { mutableIntStateOf(state.remainingSeconds) }

    LaunchedEffect(state) {
        while (state.isRunning && displaySeconds > 0) {
            delay(1000)
            displaySeconds -= 1
        }
    }

    val minutes = displaySeconds / 60
    val seconds = displaySeconds % 60
    Text(String.format("%d:%02d", minutes, seconds), style = MaterialTheme.typography.displayMedium)
    Button(onClick = onToggleTimer, modifier = Modifier.padding(top = 16.dp)) {
        Text(if (state.isRunning) "Stop" else "Start")
    }
}
