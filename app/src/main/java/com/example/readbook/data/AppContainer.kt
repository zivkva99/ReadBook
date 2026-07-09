package com.example.readbook.data

import android.content.Context
import androidx.room.Room
import com.example.readbook.scheduling.NudgeScheduler
import com.example.readbook.scheduling.NudgeSchedulingCoordinator

/** Manual DI — no framework needed at this app's size. One instance, owned by [com.example.readbook.ReadingApp]. */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    private val db: AppDatabase by lazy {
        Room.databaseBuilder(appContext, AppDatabase::class.java, "readbook.db")
            // Never fallbackToDestructiveMigration() — the entire point of this schema is
            // long-term reading history; add a real Migration when the schema ever changes.
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()
    }

    val readingConfigDao get() = db.readingConfigDao()
    val dailyProgressDao get() = db.dailyProgressDao()
    val readingSessionDao get() = db.readingSessionDao()
    val statsDao get() = db.statsDao()
    val bibleReadingProgressDao get() = db.bibleReadingProgressDao()

    val readingTimerRepository: ReadingTimerRepository by lazy {
        ReadingTimerRepository(
            dailyProgressDao = dailyProgressDao,
            readingSessionDao = readingSessionDao,
            readingConfigDao = readingConfigDao,
            statsDao = statsDao,
            clock = SystemClock,
        )
    }

    val nudgeScheduler: NudgeScheduler by lazy { NudgeScheduler(appContext) }

    val nudgeSchedulingCoordinator: NudgeSchedulingCoordinator by lazy {
        NudgeSchedulingCoordinator(readingConfigDao = readingConfigDao, scheduler = nudgeScheduler)
    }

    /** Falls back to an empty schedule (never throws) if the bundled asset is ever missing or
     * malformed — see this step's note on why a crash here must not take down the whole app. */
    val tanakhSchedule: List<ScheduleEntry> by lazy {
        try {
            val csvText = appContext.assets.open("tanakh_schedule.csv").bufferedReader().use { it.readText() }
            parseTanakhSchedule(csvText)
        } catch (e: Exception) {
            emptyList()
        }
    }

    val bibleReadingRepository: BibleReadingRepository by lazy {
        BibleReadingRepository(dao = bibleReadingProgressDao, schedule = tanakhSchedule)
    }
}
