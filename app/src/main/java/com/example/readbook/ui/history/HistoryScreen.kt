package com.example.readbook.ui.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.format.DateTimeFormatter

private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEE, MMM d")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(uiState: HistoryUiState, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            Row(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                StatBlock(label = "Current streak", value = uiState.currentStreak)
                StatBlock(label = "Total days", value = uiState.totalCompletedDays)
            }
            LazyColumn {
                items(uiState.days) { day -> DayRow(day) }
            }
        }
    }
}

@Composable
private fun RowScope.StatBlock(label: String, value: Int) {
    Column(modifier = Modifier.padding(end = 32.dp)) {
        Text(value.toString(), style = MaterialTheme.typography.headlineMedium)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DayRow(day: HistoryDayEntry) {
    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Row {
            Text(day.date.format(DATE_FORMAT), style = MaterialTheme.typography.titleSmall)
            Text(
                if (day.completed) "  ✓" else "",
                color = MaterialTheme.colorScheme.primary,
            )
        }
        day.sessions.forEach { session ->
            val minutes = session.secondsAdded / 60
            val seconds = session.secondsAdded % 60
            Text(
                "  ${minutes}m ${seconds}s",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
