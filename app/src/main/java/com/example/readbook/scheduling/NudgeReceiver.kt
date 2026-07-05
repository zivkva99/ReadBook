package com.example.readbook.scheduling

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.readbook.ReadingApp
import com.example.readbook.data.DailyProgressDao
import com.example.readbook.notifications.TimerNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Fired by AlarmManager at each hourly nudge time. A no-op if today is already completed. */
class NudgeReceiver : BroadcastReceiver() {

    // Overridable seams for tests — null means "use the real app's container / a real scope".
    internal var today: () -> LocalDate = { LocalDate.now() }
    internal var dailyProgressDaoOverride: DailyProgressDao? = null
    internal var scopeOverride: CoroutineScope? = null

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val dao = dailyProgressDaoOverride
            ?: (context.applicationContext as ReadingApp).container.dailyProgressDao
        val scope = scopeOverride ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                val row = dao.getByDate(today().toString())
                if (row?.completed != true) {
                    TimerNotifications.createChannels(context)
                    val manager = context.getSystemService(NotificationManager::class.java)
                    manager.notify(NOTIFICATION_ID_NUDGE, TimerNotifications.buildNudgeNotification(context))
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val NOTIFICATION_ID_NUDGE = 2
    }
}
