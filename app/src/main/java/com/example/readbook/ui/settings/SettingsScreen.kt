package com.example.readbook.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek

private val DAY_ORDER = listOf(
    DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY,
)

private fun DayOfWeek.shortLabel(): String = name.take(3).lowercase().replaceFirstChar { it.uppercase() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onDayToggled: (DayOfWeek) -> Unit,
    onDurationTextChanged: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(24.dp)) {
            Text("Reading days", style = MaterialTheme.typography.titleMedium)
            DAY_ORDER.forEach { day ->
                Row {
                    Checkbox(
                        checked = day in uiState.selectedDays,
                        onCheckedChange = { onDayToggled(day) },
                    )
                    Text(day.shortLabel(), modifier = Modifier.padding(top = 12.dp))
                }
            }

            Text(
                "Minutes per day",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 24.dp),
            )
            OutlinedTextField(
                value = uiState.durationMinutesText,
                onValueChange = onDurationTextChanged,
                isError = uiState.durationErrorMessage != null,
                supportingText = { uiState.durationErrorMessage?.let { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )

            Button(onClick = onSave, modifier = Modifier.padding(top = 24.dp)) {
                Text("Save")
            }
        }
    }
}
