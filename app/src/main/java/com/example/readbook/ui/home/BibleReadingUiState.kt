package com.example.readbook.ui.home

import com.example.readbook.data.BibleReadingStatus
import java.time.format.DateTimeFormatter

data class BibleReadingUiState(
    val chapterText: String?,
    val buttonEnabled: Boolean,
    val message: String?,
    val messageIsUrgent: Boolean,
    val finished: Boolean,
)

private val DISPLAY_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("d.M.yyyy")

// Unicode directional isolates — keep the LTR-formatted date from reordering unpredictably
// inside the surrounding RTL sentence.
private const val LRI = "⁦" // LEFT-TO-RIGHT ISOLATE
private const val PDI = "⁩" // POP DIRECTIONAL ISOLATE

fun deriveBibleReadingUiState(status: BibleReadingStatus): BibleReadingUiState = when (status) {
    is BibleReadingStatus.Finished -> BibleReadingUiState(
        chapterText = null, buttonEnabled = false,
        message = null, messageIsUrgent = false, finished = true,
    )
    is BibleReadingStatus.OnSchedule -> BibleReadingUiState(
        chapterText = "${status.entry.book} ${status.entry.chapterHeb}",
        buttonEnabled = true, message = null, messageIsUrgent = false, finished = false,
    )
    is BibleReadingStatus.Behind -> BibleReadingUiState(
        chapterText = "${status.entry.book} ${status.entry.chapterHeb}",
        buttonEnabled = true,
        message = "אתה בפיגור של ${status.dueCount} פרקים",
        messageIsUrgent = true,
        finished = false,
    )
    is BibleReadingStatus.Waiting -> BibleReadingUiState(
        chapterText = "${status.entry.book} ${status.entry.chapterHeb}",
        buttonEnabled = false,
        message = "נחזור לקרוא ב $LRI${status.entry.date.format(DISPLAY_DATE_FORMATTER)}$PDI",
        messageIsUrgent = false,
        finished = false,
    )
}
