package com.example.readbook.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class ScheduleEntry(val book: String, val chapterHeb: String, val date: LocalDate)

private val SCHEDULE_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("d.M.yyyy")

/**
 * Parses the exported schedule CSV: header row `Book,ChapterNum,ChapterHeb,Date`, every field
 * double-quoted, no embedded commas/quotes in any value. Pure function, no Android dependency,
 * so it's testable without Robolectric.
 */
fun parseTanakhSchedule(csvText: String): List<ScheduleEntry> =
    csvText.lineSequence()
        .drop(1) // header
        .filter { it.isNotBlank() }
        .map { line ->
            val fields = line.split(",").map { it.trim().removeSurrounding("\"") }
            ScheduleEntry(
                book = fields[0],
                chapterHeb = fields[2],
                date = LocalDate.parse(fields[3], SCHEDULE_DATE_FORMATTER),
            )
        }
        .toList()
