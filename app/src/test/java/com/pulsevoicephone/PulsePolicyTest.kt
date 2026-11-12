package com.pulsevoicephone

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PulsePolicyTest {
    private val now = Instant.parse("2026-11-12T12:00:00Z")

    @Test fun freshnessBoundary() {
        assertTrue(PulsePolicy.isFresh(PulseSample(70, now.minusSeconds(120)), now))
        assertFalse(PulsePolicy.isFresh(PulseSample(70, now.minusSeconds(121)), now))
        assertFalse(PulsePolicy.isFresh(PulseSample(70, now.plusSeconds(1)), now))
    }

    @Test fun latestUsesSampleTimeAndRejectsInvalidOrFutureReadings() {
        val selected = PulsePolicy.latest(listOf(
            PulseSample(70, now.minusSeconds(100)),
            PulseSample(80, now.minusSeconds(1)),
            PulseSample(90, now.minusSeconds(50)),
            PulseSample(0, now),
            PulseSample(301, now),
            PulseSample(100, now.plusSeconds(1))
        ), now)
        assertEquals(PulseSample(80, now.minusSeconds(1)), selected)
    }

    @Test fun emptyOrInvalidDataHasNoReading() {
        assertNull(PulsePolicy.latest(emptyList(), now))
        assertNull(PulsePolicy.latest(listOf(PulseSample(-1, now)), now))
    }
}
