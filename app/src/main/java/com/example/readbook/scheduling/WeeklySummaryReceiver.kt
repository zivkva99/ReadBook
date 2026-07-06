package com.example.readbook.scheduling

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.readbook.ReadingApp
import com.example.readbook.data.DEFAULT_ENABLED_DAYS_MASK
import com.example.readbook.data.DailyProgressDao
import com.example.readbook.data.ReadingConfigDao
import com.example.readbook.data.computeWeeklySummary
import com.example.readbook.notifications.TimerNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Fires Sunday 9:00: posts a summary of the week that just ended, then reschedules itself
 * for next Sunday — same self-chaining pattern as [RolloverReceiver]. */
class WeeklySummaryReceiver : BroadcastReceiver() {

    internal var today: () -> LocalDate = { LocalDate.now() }
    internal var dailyProgressDaoOverride: DailyProgressDao? = null
    internal var readingConfigDaoOverride: ReadingConfigDao? = null
    internal var schedulerOverride: NudgeScheduler? = null
    internal var scopeOverride: CoroutineScope? = null

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val container = (context.applicationContext as ReadingApp).container
        val dailyProgressDao = dailyProgressDaoOverride ?: container.dailyProgressDao
        val readingConfigDao = readingConfigDaoOverride ?: container.readingConfigDao
        val scheduler = schedulerOverride ?: container.nudgeScheduler
        val scope = scopeOverride ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                val firedOn = today()
                val weekStart = firedOn.minusDays(7)
                val config = readingConfigDao.getConfig()
                val enabledDaysMask = config?.enabledDaysMask ?: DEFAULT_ENABLED_DAYS_MASK
                val completedDates = dailyProgressDao.getCompletedDates().map { LocalDate.parse(it) }.toSet()
                val summary = computeWeeklySummary(enabledDaysMask, weekStart, completedDates)

                if (summary.enabledCount > 0) {
                    TimerNotifications.createChannels(context)
                    val manager = context.getSystemService(NotificationManager::class.java)
                    manager.notify(
                        NOTIFICATION_ID_WEEKLY_SUMMARY,
                        TimerNotifications.buildWeeklySummaryNotification(context, summary),
                    )
                }

                scheduler.scheduleWeeklySummary(from = firedOn)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val NOTIFICATION_ID_WEEKLY_SUMMARY = 3
    }
}
