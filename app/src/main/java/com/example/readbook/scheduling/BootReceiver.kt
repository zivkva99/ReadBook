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

/**
 * Alarms don't survive reboots — re-establish today's nudge schedule and the rollover chain.
 * Wrapped so a boot-time exception never silently bricks the day's nudges; worst case, nothing
 * gets (re)scheduled here and the next app-open self-heal check covers it.
 */
class BootReceiver : BroadcastReceiver() {

    internal var today: () -> LocalDate = { LocalDate.now() }
    internal var coordinatorOverride: NudgeSchedulingCoordinator? = null
    internal var schedulerOverride: NudgeScheduler? = null
    internal var scopeOverride: CoroutineScope? = null

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        val container = (context.applicationContext as ReadingApp).container
        val coordinator = coordinatorOverride ?: container.nudgeSchedulingCoordinator
        val scheduler = schedulerOverride ?: container.nudgeScheduler
        val scope = scopeOverride ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                val date = today()
                coordinator.ensureScheduled(date)
                coordinator.ensureBibleReminderScheduled(date)
                scheduler.scheduleRollover(from = date)
                scheduler.scheduleWeeklySummary(from = date)
            } catch (e: Exception) {
                // Never let a boot-time failure crash the receiver — next app-open self-heals.
            } finally {
                pendingResult.finish()
            }
        }
    }
}
