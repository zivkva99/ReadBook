package com.example.readbook.data

import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TanakhScheduleTest {

    @Test
    fun parseTanakhSchedule_parsesHeaderAndQuotedFields() {
        val csv = """
            "Book","ChapterNum","ChapterHeb","Date"
            "יהושע","19","י״ט","14.6.2026"
            "יהושע","20","כ׳","15.6.2026"
        """.trimIndent()

        val entries = parseTanakhSchedule(csv)

        assertEquals(2, entries.size)
        assertEquals(ScheduleEntry("יהושע", "י״ט", LocalDate.of(2026, 6, 14)), entries[0])
        assertEquals(ScheduleEntry("יהושע", "כ׳", LocalDate.of(2026, 6, 15)), entries[1])
    }

    @Test
    fun parseTanakhSchedule_parsesSingleDigitDayAndMonth() {
        val csv = "\"Book\",\"ChapterNum\",\"ChapterHeb\",\"Date\"\n\"שופטים\",\"8\",\"ח׳\",\"1.7.2026\""

        val entries = parseTanakhSchedule(csv)

        assertEquals(LocalDate.of(2026, 7, 1), entries[0].date)
    }

    @Test
    fun parseTanakhSchedule_onTheBundledAsset_hasSevenHundredTwentyFourEntries_withCorrectFirstAndLast() {
        val csvText = File("src/main/assets/tanakh_schedule.csv").readText()

        val entries = parseTanakhSchedule(csvText)

        assertEquals(724, entries.size)
        assertEquals(ScheduleEntry("יהושע", "י״ט", LocalDate.of(2026, 6, 14)), entries.first())
        assertEquals(ScheduleEntry("דברי הימים ב׳", "ל״ו", LocalDate.of(2029, 3, 21)), entries.last())
    }

    @Test
    fun parseTanakhSchedule_onTheBundledAsset_datesAreStrictlyAscending_withNoDuplicates() {
        // The entire due/behind derivation logic (BibleReadingStatus.kt) depends on this
        // invariant holding — nothing enforces it at runtime, so a future hand-edit of the CSV
        // (e.g. extending the schedule) that breaks ordering would silently corrupt dueCount
        // math with no other test catching it.
        val csvText = File("src/main/assets/tanakh_schedule.csv").readText()

        val entries = parseTanakhSchedule(csvText)

        val strictlyAscending = entries.zipWithNext().all { (a, b) -> a.date.isBefore(b.date) }
        assertTrue(strictlyAscending)
    }
}
