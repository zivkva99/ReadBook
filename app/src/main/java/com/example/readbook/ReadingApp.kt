package com.example.readbook

import android.app.Application
import com.example.readbook.data.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate

class ReadingApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Self-heal on every app open: ensures today's nudges and the rollover chain are
        // scheduled even if the midnight job never got to run (OEM battery killers, a missed
        // boot receiver, etc.) — not solely reliant on any single scheduling path having fired.
        appScope.launch {
            val today = LocalDate.now()
            container.nudgeSchedulingCoordinator.ensureScheduled(today)
            container.nudgeScheduler.scheduleRollover(from = today)
        }
    }
}
