package com.example.readbook.data

import java.time.LocalDate

sealed interface BibleReadingStatus {
    data class OnSchedule(val entry: ScheduleEntry) : BibleReadingStatus
    data class Behind(val entry: ScheduleEntry, val dueCount: Int) : BibleReadingStatus
    data class Waiting(val entry: ScheduleEntry) : BibleReadingStatus
    data object Finished : BibleReadingStatus
}

/**
 * [cursorIndex] is the index of the next unread chapter. The schedule's dates never move (no
 * reflow on a missed day) - falling behind means catching up one chapter at a time, never
 * skipping ahead to "today's" chapter.
 */
fun deriveBibleReadingStatus(
    schedule: List<ScheduleEntry>,
    cursorIndex: Int,
    today: LocalDate,
): BibleReadingStatus {
    val entry = schedule.getOrNull(cursorIndex) ?: return BibleReadingStatus.Finished
    return when {
        entry.date.isEqual(today) -> BibleReadingStatus.OnSchedule(entry)
        entry.date.isBefore(today) -> {
            val lastDueIndex = schedule.indexOfLast { !it.date.isAfter(today) }
            val dueCount = lastDueIndex - cursorIndex + 1
            BibleReadingStatus.Behind(entry, dueCount)
        }
        else -> BibleReadingStatus.Waiting(entry)
    }
}
