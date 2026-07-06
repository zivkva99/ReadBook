package com.example.readbook

import android.app.Application
import com.example.readbook.data.AppContainer
import com.example.readbook.data.ensureConfigSeeded
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
        // Self-heal on every app open: seeds default config on first launch (otherwise nothing
        // saves one until a Settings screen exists), reconciles a session left dangling by a
        // process kill (the foreground service dies with the process — activeSessionStartedAt
        // would otherwise sit stuck forever with no service left to ever finish it), then ensures
        // today's nudges, the rollover chain, and the weekly summary alarm are all scheduled even
        // if the midnight/boot jobs never got to run (OEM battery killers, a missed boot
        // receiver, etc.) — not solely reliant on any single scheduling path.
        appScope.launch {
            ensureConfigSeeded(container.readingConfigDao)
            container.readingTimerRepository.reconcileCrashedSession()
            val today = LocalDate.now()
            container.nudgeSchedulingCoordinator.ensureScheduled(today)
            container.nudgeScheduler.scheduleRollover(from = today)
            container.nudgeScheduler.scheduleWeeklySummary(from = today)
        }
    }
}
