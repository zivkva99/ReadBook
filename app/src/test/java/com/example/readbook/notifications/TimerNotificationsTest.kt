package com.example.readbook.notifications

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.readbook.MainActivity
import com.example.readbook.data.ScheduleEntry
import com.example.readbook.data.WeeklySummary
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimerNotificationsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Test
    fun createChannels_registersNudgeChannel_withDefaultImportanceAndSound() {
        TimerNotifications.createChannels(context)

        val channel = manager.getNotificationChannel(TimerNotifications.CHANNEL_NUDGE)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
    }

    @Test
    fun createChannels_registersTimerChannel_withLowImportance_soItCanBeMutedSeparately() {
        TimerNotifications.createChannels(context)

        val channel = manager.getNotificationChannel(TimerNotifications.CHANNEL_TIMER)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    @Test
    fun createChannels_registersCompletionChannel_withDefaultImportance() {
        TimerNotifications.createChannels(context)

        val channel = manager.getNotificationChannel(TimerNotifications.CHANNEL_COMPLETION)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
    }

    @Test
    fun buildTimerNotification_usesTimerChannel_andShowsRemainingMinutes() {
        TimerNotifications.createChannels(context)

        val notification = TimerNotifications.buildTimerNotification(context, remainingSeconds = 125)

        assertEquals(TimerNotifications.CHANNEL_TIMER, notification.channelId)
        assertEquals("2 min left", shadowContentText(notification))
    }

    @Test
    fun buildCompletionNotification_usesInvitationalCopy() {
        TimerNotifications.createChannels(context)

        val notification = TimerNotifications.buildCompletionNotification(context)

        assertEquals(TimerNotifications.CHANNEL_COMPLETION, notification.channelId)
        assertEquals("Nice — today's reading is done", shadowContentText(notification))
    }

    @Test
    fun buildNudgeNotification_usesInvitationalCopy_notUrgent() {
        TimerNotifications.createChannels(context)

        val notification = TimerNotifications.buildNudgeNotification(context)

        assertEquals(TimerNotifications.CHANNEL_NUDGE, notification.channelId)
        assertEquals("15 minutes today?", shadowContentText(notification))
    }

    @Test
    fun buildNudgeNotification_hasStartAndSnoozeActions() {
        TimerNotifications.createChannels(context)

        val notification = TimerNotifications.buildNudgeNotification(context)

        assertEquals(2, notification.actions.size)
        assertEquals("Start", notification.actions[0].title)
        assertEquals("Snooze 15m", notification.actions[1].title)
    }

    @Test
    fun createChannels_registersWeeklySummaryChannel_withDefaultImportance() {
        TimerNotifications.createChannels(context)

        val channel = manager.getNotificationChannel(TimerNotifications.CHANNEL_WEEKLY_SUMMARY)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
    }

    @Test
    fun buildWeeklySummaryNotification_usesWeeklySummaryChannel_andReportsTheCounts() {
        TimerNotifications.createChannels(context)

        val notification = TimerNotifications.buildWeeklySummaryNotification(
            context, WeeklySummary(completedCount = 4, enabledCount = 5),
        )

        assertEquals(TimerNotifications.CHANNEL_WEEKLY_SUMMARY, notification.channelId)
        assertEquals("You read 4/5 days last week", shadowContentText(notification))
    }

    @Test
    fun createChannels_registersBibleReadingChannel_withDefaultImportance() {
        TimerNotifications.createChannels(context)

        val channel = manager.getNotificationChannel(TimerNotifications.CHANNEL_BIBLE_READING)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
    }

    @Test
    fun buildBibleReadingReminderNotification_usesBibleReadingChannel_andShowsTheChapter() {
        TimerNotifications.createChannels(context)
        val entry = ScheduleEntry("יהושע", "י״ט", LocalDate.of(2026, 6, 14))

        val notification = TimerNotifications.buildBibleReadingReminderNotification(context, entry)

        assertEquals(TimerNotifications.CHANNEL_BIBLE_READING, notification.channelId)
        assertEquals("יהושע י״ט", shadowContentText(notification))
    }

    @Test
    fun buildBibleReadingReminderNotification_tapOpensMainActivity() {
        TimerNotifications.createChannels(context)
        val entry = ScheduleEntry("יהושע", "י״ט", LocalDate.of(2026, 6, 14))

        val notification = TimerNotifications.buildBibleReadingReminderNotification(context, entry)

        val shadowPendingIntent = shadowOf(notification.contentIntent)
        assertEquals(MainActivity::class.java.name, shadowPendingIntent.savedIntent.component?.className)
    }

    @Test
    fun buildBibleReadingReminderNotification_tapDoesNotStackADuplicateActivity() {
        // This is the first notification in this codebase that opens an Activity at all (grepped
        // the whole app/src/main tree for setContentIntent/PendingIntent.getActivity - zero prior
        // hits). MainActivity has no launchMode set (defaults to "standard"), so without these
        // flags, tapping the notification while an instance is already open/backgrounded would
        // push a second MainActivity instance onto the task instead of resuming the existing one.
        TimerNotifications.createChannels(context)
        val entry = ScheduleEntry("יהושע", "י״ט", LocalDate.of(2026, 6, 14))

        val notification = TimerNotifications.buildBibleReadingReminderNotification(context, entry)

        val flags = shadowOf(notification.contentIntent).savedIntent.flags
        assertTrue(flags and android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(flags and android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
    }

    private fun shadowContentText(notification: android.app.Notification): String =
        notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
}
