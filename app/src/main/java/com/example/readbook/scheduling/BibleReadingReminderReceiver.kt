package com.example.readbook.scheduling

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.readbook.ReadingApp
import com.example.readbook.data.BibleReadingRepository
import com.example.readbook.data.BibleReadingStatus
import com.example.readbook.notifications.TimerNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Fired by AlarmManager at each hourly reminder time. A no-op unless a chapter is actually
 * due (OnSchedule or Behind) - never posts for Waiting or Finished. */
class BibleReadingReminderReceiver : BroadcastReceiver() {

    internal var today: () -> LocalDate = { LocalDate.now() }
    internal var repositoryOverride: BibleReadingRepository? = null
    internal var scopeOverride: CoroutineScope? = null

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val repository = repositoryOverride
            ?: (context.applicationContext as ReadingApp).container.bibleReadingRepository
        val scope = scopeOverride ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                val entry = when (val status = repository.currentStatus(today())) {
                    is BibleReadingStatus.OnSchedule -> status.entry
                    is BibleReadingStatus.Behind -> status.entry
                    is BibleReadingStatus.Waiting, BibleReadingStatus.Finished -> null
                }
                if (entry != null) {
                    TimerNotifications.createChannels(context)
                    val manager = context.getSystemService(NotificationManager::class.java)
                    manager.notify(
                        NOTIFICATION_ID_BIBLE_READING_REMINDER,
                        TimerNotifications.buildBibleReadingReminderNotification(context, entry),
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val NOTIFICATION_ID_BIBLE_READING_REMINDER = 4
    }
}
