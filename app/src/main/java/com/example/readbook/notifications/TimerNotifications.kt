package com.example.readbook.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.readbook.MainActivity
import com.example.readbook.R
import com.example.readbook.data.ScheduleEntry
import com.example.readbook.data.WeeklySummary
import com.example.readbook.scheduling.NudgeReceiver
import com.example.readbook.scheduling.NudgeScheduler
import com.example.readbook.service.ReadingTimerService

/**
 * Three separate channels so the persistent timer notification can be muted
 * independently of the hourly nudges and the completion confirmation.
 */
object TimerNotifications {
    const val CHANNEL_NUDGE = "nudge"
    const val CHANNEL_TIMER = "timer"
    const val CHANNEL_COMPLETION = "completion"
    const val CHANNEL_WEEKLY_SUMMARY = "weekly_summary"
    const val CHANNEL_BIBLE_READING = "bible_reading_reminder"

    private const val START_ACTION_REQUEST_CODE = 300
    private const val SNOOZE_ACTION_REQUEST_CODE = 301
    private const val OPEN_APP_REQUEST_CODE = 302

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
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_WEEKLY_SUMMARY, "Weekly summary", NotificationManager.IMPORTANCE_DEFAULT)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_BIBLE_READING, "Bible reading reminder", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun buildNudgeNotification(context: Context): Notification {
        val startIntent = Intent(context, ReadingTimerService::class.java).setAction(ReadingTimerService.ACTION_START)
        val startPendingIntent = PendingIntent.getForegroundService(
            context, START_ACTION_REQUEST_CODE, startIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val snoozeIntent = Intent(context, NudgeReceiver::class.java)
            .setAction(NudgeScheduler.ACTION_NUDGE)
            .putExtra(NudgeReceiver.EXTRA_SNOOZE, true)
        val snoozePendingIntent = PendingIntent.getBroadcast(
            context, SNOOZE_ACTION_REQUEST_CODE, snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(context, CHANNEL_NUDGE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentText("15 minutes today?")
            .setAutoCancel(true)
            .addAction(R.drawable.ic_launcher_foreground, "Start", startPendingIntent)
            .addAction(R.drawable.ic_launcher_foreground, "Snooze 15m", snoozePendingIntent)
            .build()
    }

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

    fun buildWeeklySummaryNotification(context: Context, summary: WeeklySummary): Notification =
        NotificationCompat.Builder(context, CHANNEL_WEEKLY_SUMMARY)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentText("You read ${summary.completedCount}/${summary.enabledCount} days last week")
            .setAutoCancel(true)
            .build()

    fun buildBibleReadingReminderNotification(context: Context, entry: ScheduleEntry): Notification {
        // CLEAR_TOP + SINGLE_TOP: the first notification in this app to open an Activity at all.
        // MainActivity has no launchMode set, so without these a tap while an instance is already
        // open/backgrounded would stack a duplicate MainActivity instead of resuming it.
        val activityIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val contentIntent = PendingIntent.getActivity(
            context, OPEN_APP_REQUEST_CODE, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_BIBLE_READING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentText("${entry.book} ${entry.chapterHeb}")
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
    }
}
