package com.example.readbook.scheduling

import com.example.readbook.data.Clock

internal class FakeClock(var millis: Long = 0L) : Clock {
    override fun nowMillis(): Long = millis
}
