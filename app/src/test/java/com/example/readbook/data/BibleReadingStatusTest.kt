package com.example.readbook.data

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BibleReadingStatusTest {

    // One entry per Sun-Thu, mirroring the real schedule's cadence.
    private val schedule = listOf(
        ScheduleEntry("א", "א׳", LocalDate.of(2026, 7, 5)),  // Sunday
        ScheduleEntry("א", "ב׳", LocalDate.of(2026, 7, 6)),  // Monday
        ScheduleEntry("א", "ג׳", LocalDate.of(2026, 7, 7)),  // Tuesday
        ScheduleEntry("א", "ד׳", LocalDate.of(2026, 7, 8)),  // Wednesday
        ScheduleEntry("א", "ה׳", LocalDate.of(2026, 7, 9)),  // Thursday
        ScheduleEntry("א", "ו׳", LocalDate.of(2026, 7, 12)), // next Sunday
    )

    @Test
    fun onTheExactScheduledDate_yieldsOnSchedule_withNoBehindCount() {
        val status = deriveBibleReadingStatus(schedule, cursorIndex = 2, today = LocalDate.of(2026, 7, 7))

        assertEquals(BibleReadingStatus.OnSchedule(schedule[2]), status)
    }

    @Test
    fun beforeTheScheduledDate_yieldsWaiting() {
        val status = deriveBibleReadingStatus(schedule, cursorIndex = 2, today = LocalDate.of(2026, 7, 6))

        assertEquals(BibleReadingStatus.Waiting(schedule[2]), status)
    }

    @Test
    fun oneDayBehind_midWeek_dueCountIncludesTodaysNewlyDueChapter() {
        // Cursor still on Tuesday's chapter; today is Wednesday, so Wednesday's own chapter has
        // also become due - dueCount counts both the overdue Tuesday one and today's Wednesday one.
        val status = deriveBibleReadingStatus(schedule, cursorIndex = 2, today = LocalDate.of(2026, 7, 8))

        assertEquals(BibleReadingStatus.Behind(schedule[2], dueCount = 2), status)
    }

    @Test
    fun severalDaysBehind_dueCountCountsAllUnreadDueChapters() {
        // Cursor still on Sunday's chapter, today is Thursday: Sun/Mon/Tue/Wed/Thu are all due = 5.
        val status = deriveBibleReadingStatus(schedule, cursorIndex = 0, today = LocalDate.of(2026, 7, 9))

        assertEquals(BibleReadingStatus.Behind(schedule[0], dueCount = 5), status)
    }

    @Test
    fun missedThursday_todayIsFridayNonReadingDay_stillReportsBehindWithDueCountOne() {
        // Regression case: Thursday's chapter (index 4) unread, today is Friday. No new chapter
        // has come due since Thursday, but it's still a prior day, not "today" - must be Behind,
        // not OnSchedule, even though dueCount only comes out to 1.
        val friday = LocalDate.of(2026, 7, 10)

        val status = deriveBibleReadingStatus(schedule, cursorIndex = 4, today = friday)

        assertEquals(BibleReadingStatus.Behind(schedule[4], dueCount = 1), status)
    }

    @Test
    fun caughtUpMidWeek_nextChapterInTheFuture_yieldsWaitingWithThatChaptersDate() {
        // Just read Wednesday's chapter; Thursday's isn't due until Thursday.
        val status = deriveBibleReadingStatus(schedule, cursorIndex = 4, today = LocalDate.of(2026, 7, 8))

        assertEquals(BibleReadingStatus.Waiting(schedule[4]), status)
    }

    @Test
    fun cursorPastTheLastEntry_yieldsFinished() {
        val status = deriveBibleReadingStatus(schedule, cursorIndex = schedule.size, today = LocalDate.of(2026, 8, 1))

        assertIs<BibleReadingStatus.Finished>(status)
    }
}
