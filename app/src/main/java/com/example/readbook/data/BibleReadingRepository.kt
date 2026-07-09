package com.example.readbook.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

class BibleReadingRepository(
    private val dao: BibleReadingProgressDao,
    private val schedule: List<ScheduleEntry>,
) {
    fun observeStatus(today: () -> LocalDate): Flow<BibleReadingStatus> =
        dao.observeProgress().map { progress ->
            deriveBibleReadingStatus(schedule, progress?.cursorIndex ?: 0, today())
        }

    suspend fun currentStatus(today: LocalDate): BibleReadingStatus {
        val cursorIndex = dao.getProgress()?.cursorIndex ?: 0
        return deriveBibleReadingStatus(schedule, cursorIndex, today)
    }

    suspend fun markRead() {
        val cursorIndex = dao.getProgress()?.cursorIndex ?: 0
        dao.upsert(BibleReadingProgress(cursorIndex = cursorIndex + 1))
    }

    /** Reverses the last [markRead] — used by the Home screen's short-lived "undo" action.
     * Never goes negative; a no-op at cursor 0. */
    suspend fun undoMarkRead() {
        val cursorIndex = dao.getProgress()?.cursorIndex ?: 0
        if (cursorIndex > 0) {
            dao.upsert(BibleReadingProgress(cursorIndex = cursorIndex - 1))
        }
    }
}
