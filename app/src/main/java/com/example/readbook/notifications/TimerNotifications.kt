package com.example.readbook.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.example.readbook.R

/**
 * Three separate channels so the persistent timer notification can be muted
 * independently of the hourly nudges and the completion confirmation.
 */
object TimerNotifications {
    const val CHANNEL_NUDGE = "nudge"
    const val CHANNEL_TIMER = "timer"
    const val CHANNEL_COMPLETION = "completion"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_NUDGE, "Reading reminders", NotificationManager.IMPORTANCE_DEFAULT)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_TIMER, "Reading timer", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_COMPLETION, "Reading completed", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun buildNudgeNotification(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_NUDGE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentText("15 minutes today?")
            .setAutoCancel(true)
            .build()

    fun buildTimerNotification(context: Context, remainingSeconds: Int): Notification {
        val minutes = remainingSeconds / 60
        return NotificationCompat.Builder(context, CHANNEL_TIMER)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentText("$minutes min left")
            .setOngoing(true)
            .build()
    }

    fun buildCompletionNotification(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_COMPLETION)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentText("Nice — today's reading is done")
            .setAutoCancel(true)
            .build()
}
