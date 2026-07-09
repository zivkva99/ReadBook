package com.example.readbook.scheduling

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.readbook.data.AppDatabase
import com.example.readbook.data.BibleReadingProgress
import com.example.readbook.data.BibleReadingRepository
import com.example.readbook.data.ScheduleEntry
import com.example.readbook.notifications.TimerNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BibleReadingReminderReceiverTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val schedule = listOf(
        ScheduleEntry("יהושע", "י״ט", LocalDate.of(2026, 6, 14)),
        ScheduleEntry("יהושע", "כ׳", LocalDate.of(2026, 6, 15)),
    )

    private fun dispatch(receiver: BibleReadingReminderReceiver, action: String) {
        context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        context.sendBroadcast(Intent(action))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun onReceive_chapterDue_postsAReminderNotification() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryCoroutineContext(StandardTestDispatcher(testScheduler))
            .build()
        val receiver = BibleReadingReminderReceiver()
        receiver.today = { LocalDate.of(2026, 6, 14) } // exactly on schedule[0]'s date
        receiver.repositoryOverride = BibleReadingRepository(db.bibleReadingProgressDao(), schedule)
        receiver.scopeOverride = CoroutineScope(StandardTestDispatcher(testScheduler))

        dispatch(receiver, NudgeScheduler.ACTION_BIBLE_REMINDER)
        testScheduler.advanceUntilIdle()

        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = manager.activeNotifications
            .firstOrNull { it.id == BibleReadingReminderReceiver.NOTIFICATION_ID_BIBLE_READING_REMINDER }
        assertEquals(TimerNotifications.CHANNEL_BIBLE_READING, notification?.notification?.channelId)

        db.close()
    }

    @Test
    fun onReceive_notYetDue_postsNothing() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryCoroutineContext(StandardTestDispatcher(testScheduler))
            .build()
        val receiver = BibleReadingReminderReceiver()
        receiver.today = { LocalDate.of(2026, 6, 13) } // day before schedule[0]'s date
        receiver.repositoryOverride = BibleReadingRepository(db.bibleReadingProgressDao(), schedule)
        receiver.scopeOverride = CoroutineScope(StandardTestDispatcher(testScheduler))

        dispatch(receiver, NudgeScheduler.ACTION_BIBLE_REMINDER)
        testScheduler.advanceUntilIdle()

        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = manager.activeNotifications
            .firstOrNull { it.id == BibleReadingReminderReceiver.NOTIFICATION_ID_BIBLE_READING_REMINDER }
        assertNull(notification)

        db.close()
    }

    @Test
    fun onReceive_allChaptersFinished_postsNothing() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryCoroutineContext(StandardTestDispatcher(testScheduler))
            .build()
        db.bibleReadingProgressDao().upsert(BibleReadingProgress(cursorIndex = schedule.size))
        val receiver = BibleReadingReminderReceiver()
        receiver.today = { LocalDate.of(2026, 6, 20) }
        receiver.repositoryOverride = BibleReadingRepository(db.bibleReadingProgressDao(), schedule)
        receiver.scopeOverride = CoroutineScope(StandardTestDispatcher(testScheduler))

        dispatch(receiver, NudgeScheduler.ACTION_BIBLE_REMINDER)
        testScheduler.advanceUntilIdle()

        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = manager.activeNotifications
            .firstOrNull { it.id == BibleReadingReminderReceiver.NOTIFICATION_ID_BIBLE_READING_REMINDER }
        assertNull(notification)

        db.close()
    }
}
