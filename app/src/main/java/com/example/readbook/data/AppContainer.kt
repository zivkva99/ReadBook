package com.example.readbook.data

import android.content.Context
import androidx.room.Room
import com.example.readbook.scheduling.NudgeScheduler
import com.example.readbook.scheduling.NudgeSchedulingCoordinator

/** Manual DI — no framework needed at this app's size. One instance, owned by [com.example.readbook.ReadingApp]. */
class AppContainer(context: Context) {

    private val db: AppDatabase by lazy {
        Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "readbook.db")
            // Never fallbackToDestructiveMigration() — the entire point of this schema is
            // long-term reading history; add a real Migration when the schema ever changes.
            .build()
    }

    val readingConfigDao get() = db.readingConfigDao()
    val dailyProgressDao get() = db.dailyProgressDao()
    val readingSessionDao get() = db.readingSessionDao()
    val statsDao get() = db.statsDao()

    val readingTimerRepository: ReadingTimerRepository by lazy {
        ReadingTimerRepository(
            dailyProgressDao = dailyProgressDao,
            readingSessionDao = readingSessionDao,
            readingConfigDao = readingConfigDao,
            statsDao = statsDao,
            clock = SystemClock,
        )
    }

    val nudgeScheduler: NudgeScheduler by lazy { NudgeScheduler(context.applicationContext) }

    val nudgeSchedulingCoordinator: NudgeSchedulingCoordinator by lazy {
        NudgeSchedulingCoordinator(readingConfigDao = readingConfigDao, scheduler = nudgeScheduler)
    }
}
