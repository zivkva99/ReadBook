package com.example.readbook.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.readbook.ReadingApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Fires nightly at 00:01: schedules the new day's nudges (if enabled) and reschedules itself. */
class RolloverReceiver : BroadcastReceiver() {

    // Fires at 00:01, so LocalDate.now() at that moment IS the new day — no "tomorrow" math needed.
    internal var newDay: () -> LocalDate = { LocalDate.now() }
    internal var coordinatorOverride: NudgeSchedulingCoordinator? = null
    internal var schedulerOverride: NudgeScheduler? = null
    internal var scopeOverride: CoroutineScope? = null

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val container = (context.applicationContext as ReadingApp).container
        val coordinator = coordinatorOverride ?: container.nudgeSchedulingCoordinator
        val scheduler = schedulerOverride ?: container.nudgeScheduler
        val scope = scopeOverride ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                val today = newDay()
                coordinator.ensureScheduled(today)
                scheduler.scheduleRollover(from = today)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
