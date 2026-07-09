package com.example.readbook.scheduling

import com.example.readbook.data.ReadingConfigDao
import java.time.LocalDate

/**
 * Shared self-heal entry point used by [BootReceiver], [RolloverReceiver], and app-open —
 * "does today have its alarms scheduled? If not, schedule them" — so nudges recover even if
 * the midnight rollover job never got to run (OEM battery killers, missed boot, etc.).
 */
class NudgeSchedulingCoordinator(
    private val readingConfigDao: ReadingConfigDao,
    private val scheduler: NudgeScheduler,
) {
    suspend fun ensureScheduled(date: LocalDate) {
        val config = readingConfigDao.getConfig() ?: return // no config saved yet — nothing to schedule
        scheduler.scheduleNudgesForToday(date, config)
    }

    suspend fun ensureBibleReminderScheduled(date: LocalDate) {
        val config = readingConfigDao.getConfig() ?: return
        scheduler.scheduleBibleReminderHoursForToday(date, config)
    }
}
