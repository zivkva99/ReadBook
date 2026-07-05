package com.example.readbook

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.readbook.ui.home.HomeScreen
import com.example.readbook.ui.home.HomeViewModel
import com.example.readbook.ui.home.HomeViewModelFactory
import com.example.readbook.ui.theme.ReadBookTheme

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: HomeViewModel

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.setNotificationsDenied(!granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = (application as ReadingApp).container
        viewModel = ViewModelProvider(
            this,
            HomeViewModelFactory(container.dailyProgressDao, container.readingConfigDao),
        )[HomeViewModel::class.java]

        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        viewModel.setNotificationsDenied(!granted)
        if (!granted) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            ReadBookTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                HomeScreen(uiState = uiState, onToggleTimer = { viewModel.onToggleTimer(this) })
            }
        }
    }
}
