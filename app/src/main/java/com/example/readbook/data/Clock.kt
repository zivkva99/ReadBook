package com.example.readbook.data

/** Seam for deterministic time in tests — real code uses [SystemClock]. */
interface Clock {
    fun nowMillis(): Long
}

object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
