package com.pulsevoicephone

import java.time.Duration
import java.time.Instant

data class PulseSample(val bpm: Long, val time: Instant)

object PulsePolicy {
    const val POLL_INTERVAL_MS = 10_000L
    const val FRESH_SECONDS = 120L

    fun latest(samples: Iterable<PulseSample>, now: Instant): PulseSample? =
        samples.filter { it.bpm in 1..300 && !it.time.isAfter(now) }.maxByOrNull { it.time }

    fun isFresh(sample: PulseSample, now: Instant): Boolean {
        val age = Duration.between(sample.time, now)
        return !age.isNegative && age <= Duration.ofSeconds(FRESH_SECONDS)
    }
}
