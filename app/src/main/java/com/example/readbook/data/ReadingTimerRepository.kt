package com.example.readbook.data

import java.time.LocalDate

/**
 * Orchestrates the timer's start/stop/resume/completion and crash recovery.
 * Uses a timestamp-delta model: only [DailyProgress.activeSessionStartedAt] is written
 * while a session runs, and elapsed time is computed from it at Stop — never a periodic
 * tick-decrement, so there's no data-loss window and no drift under Doze.
 */
class ReadingTimerRepository(
    private val dailyProgressDao: DailyProgressDao,
    private val readingSessionDao: ReadingSessionDao,
    private val readingConfigDao: ReadingConfigDao,
    private val statsDao: StatsDao,
    private val clock: Clock,
) {

    suspend fun start(date: LocalDate): DailyProgress {
        val key = date.toString()
        val existing = dailyProgressDao.getByDate(key)
        val row = if (existing != null) {
            existing.copy(activeSessionStartedAt = clock.nowMillis())
        } else {
            val config = readingConfigDao.getConfig()
            val targetSeconds = config?.targetSeconds ?: DEFAULT_TARGET_SECONDS
            DailyProgress(
                date = key,
                targetSeconds = targetSeconds,
                remainingSeconds = targetSeconds,
                completed = false,
                completedAt = null,
                activeSessionStartedAt = clock.nowMillis(),
            )
        }
        dailyProgressDao.upsert(row)
        return row
    }

    suspend fun stop(date: LocalDate): DailyProgress? {
        val row = dailyProgressDao.getByDate(date.toString()) ?: return null
        if (row.activeSessionStartedAt == null) return row // idempotent — nothing running
        return finishSession(row)
    }

    /** Call on app launch: finds any session left dangling by a process kill and closes it out. */
    suspend fun reconcileCrashedSession(): DailyProgress? {
        val dangling = dailyProgressDao.getActiveSession() ?: return null
        return finishSession(dangling)
    }

    private suspend fun finishSession(row: DailyProgress): DailyProgress {
        val startedAt = requireNotNull(row.activeSessionStartedAt)
        val now = clock.nowMillis()
        val elapsedSeconds = ((now - startedAt) / 1000L).toInt()
        val newRemaining = (row.remainingSeconds - elapsedSeconds).coerceAtLeast(0)
        val justCompleted = newRemaining == 0

        readingSessionDao.insert(
            ReadingSession(
                date = row.date,
                startedAt = startedAt,
                endedAt = now,
                secondsAdded = elapsedSeconds,
            )
        )

        val updated = row.copy(
            remainingSeconds = newRemaining,
            completed = justCompleted,
            completedAt = if (justCompleted) now else row.completedAt,
            activeSessionStartedAt = null,
        )
        dailyProgressDao.upsert(updated)

        if (justCompleted) {
            updateStatsOnCompletion(row.date)
        }

        return updated
    }

    private suspend fun updateStatsOnCompletion(completedDate: String) {
        val priorCompletedDates = dailyProgressDao.getCompletedDates().map { LocalDate.parse(it) }.toSet()
        val allCompletedDates = priorCompletedDates + LocalDate.parse(completedDate)
        val config = readingConfigDao.getConfig()
        val enabledDaysMask = config?.enabledDaysMask ?: DEFAULT_ENABLED_DAYS_MASK
        val newStreak = StreakCalculator.calculate(allCompletedDates, enabledDaysMask, LocalDate.parse(completedDate))

        val priorStats = statsDao.getStats() ?: Stats(totalCompletedDays = 0, currentStreak = 0)
        statsDao.upsert(
            priorStats.copy(
                totalCompletedDays = priorStats.totalCompletedDays + 1,
                currentStreak = newStreak,
            )
        )
    }
}
