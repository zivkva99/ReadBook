package com.example.readbook.notifications

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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

    private fun shadowContentText(notification: android.app.Notification): String =
        notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
}
