package com.example.readbook.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.example.readbook.ReadingApp
import com.example.readbook.data.ReadingTimerRepository
import com.example.readbook.notifications.TimerNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Foreground service owning the running timer. Nudges (a separate BroadcastReceiver,
 * scheduled by AlarmManager) never touch this class directly — they just check whether
 * today is already completed before posting.
 */
class ReadingTimerService : Service() {

    // internal + var, not private, so tests can substitute fakes after onCreate() runs
    // the production defaults below — this is the seam, not a design mistake.
    internal lateinit var repository: ReadingTimerRepository
    internal lateinit var scope: CoroutineScope
    internal var today: () -> LocalDate = { LocalDate.now() }

    // Fires stop() automatically once the countdown naturally elapses, so completion (Stats
    // update, cancelling today's nudges, the completion notification) doesn't depend on the
    // user happening to press Stop right when time's up. Cancelled on manual Stop and replaced
    // on every Start, so a stale job from a prior segment can never cut a later resume short.
    private var autoCompleteJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        TimerNotifications.createChannels(this)
        repository = (application as ReadingApp).container.readingTimerRepository
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart()
            ACTION_STOP -> handleStop()
        }
        return START_NOT_STICKY
    }

    private fun handleStart() {
        // Placeholder — startForeground() must be called synchronously (before the suspending
        // repository.start() below) to satisfy Android's foreground-service contract. Corrected
        // via updateNotification() within milliseconds, once the real remaining time is known.
        startForeground(
            NOTIFICATION_ID_TIMER,
            TimerNotifications.buildTimerNotification(this, 0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        autoCompleteJob?.cancel()
        autoCompleteJob = scope.launch {
            val row = repository.start(today())
            updateNotification(row.remainingSeconds)
            var remaining = row.remainingSeconds
            while (remaining > 0) {
                val step = minOf(NOTIFICATION_UPDATE_INTERVAL_SECONDS, remaining)
                delay(step * 1000L)
                remaining -= step
                if (remaining > 0) updateNotification(remaining)
            }
            finishAndStopService()
        }
    }

    private fun updateNotification(remainingSeconds: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID_TIMER, TimerNotifications.buildTimerNotification(this, remainingSeconds))
    }

    private fun handleStop() {
        autoCompleteJob?.cancel()
        scope.launch {
            repository.stop(today())
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun finishAndStopService() {
        repository.stop(today())
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_START = "com.example.readbook.action.START_TIMER"
        const val ACTION_STOP = "com.example.readbook.action.STOP_TIMER"
        const val NOTIFICATION_ID_TIMER = 1
        private const val NOTIFICATION_UPDATE_INTERVAL_SECONDS = 30
    }
}
