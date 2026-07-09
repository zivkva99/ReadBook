package com.example.readbook.ui.home

import com.example.readbook.data.BibleReadingStatus
import com.example.readbook.data.ScheduleEntry
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class BibleReadingUiStateTest {

    private val entry = ScheduleEntry("יהושע", "י״ט", LocalDate.of(2026, 6, 14))

    @Test
    fun onSchedule_showsChapterAndEnabledButton_noMessage() {
        val state = deriveBibleReadingUiState(BibleReadingStatus.OnSchedule(entry))

        assertEquals(
            BibleReadingUiState(
                chapterText = "יהושע י״ט", buttonEnabled = true,
                message = null, messageIsUrgent = false, finished = false,
            ),
            state,
        )
    }

    @Test
    fun behind_showsChapterAndEnabledButton_withUrgentBehindCountMessage() {
        val state = deriveBibleReadingUiState(BibleReadingStatus.Behind(entry, dueCount = 3))

        assertEquals(
            BibleReadingUiState(
                chapterText = "יהושע י״ט", buttonEnabled = true,
                message = "אתה בפיגור של 3 פרקים", messageIsUrgent = true, finished = false,
            ),
            state,
        )
    }

    @Test
    fun waiting_showsUpcomingChapterAndDisabledButton_withReturnDateMessage_dateIsBidiIsolated() {
        val state = deriveBibleReadingUiState(BibleReadingStatus.Waiting(entry))

        assertEquals(
            BibleReadingUiState(
                chapterText = "יהושע י״ט", buttonEnabled = false,
                message = "נחזור לקרוא ב ⁦14.6.2026⁩", messageIsUrgent = false, finished = false,
            ),
            state,
        )
    }

    @Test
    fun finished_hasNoChapterOrMessage_andIsFlaggedFinished() {
        val state = deriveBibleReadingUiState(BibleReadingStatus.Finished)

        assertEquals(
            BibleReadingUiState(
                chapterText = null, buttonEnabled = false,
                message = null, messageIsUrgent = false, finished = true,
            ),
            state,
        )
    }
}
