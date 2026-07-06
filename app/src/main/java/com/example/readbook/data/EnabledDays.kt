package com.example.readbook.data

import java.time.DayOfWeek
import java.time.LocalDate

/** Sun=1, Mon=2, Tue=4, Wed=8, Thu=16, Fri=32, Sat=64 — matches [ReadingConfig.enabledDaysMask]. */
private fun bitFor(day: DayOfWeek): Int = when (day) {
    DayOfWeek.SUNDAY -> 0b0000001
    DayOfWeek.MONDAY -> 0b0000010
    DayOfWeek.TUESDAY -> 0b0000100
    DayOfWeek.WEDNESDAY -> 0b0001000
    DayOfWeek.THURSDAY -> 0b0010000
    DayOfWeek.FRIDAY -> 0b0100000
    DayOfWeek.SATURDAY -> 0b1000000
}

fun isEnabledDay(date: LocalDate, enabledDaysMask: Int): Boolean =
    (enabledDaysMask and bitFor(date.dayOfWeek)) != 0

fun daysToMask(days: Set<DayOfWeek>): Int = days.fold(0) { mask, day -> mask or bitFor(day) }

fun maskToDays(enabledDaysMask: Int): Set<DayOfWeek> =
    DayOfWeek.entries.filterTo(mutableSetOf()) { (enabledDaysMask and bitFor(it)) != 0 }
