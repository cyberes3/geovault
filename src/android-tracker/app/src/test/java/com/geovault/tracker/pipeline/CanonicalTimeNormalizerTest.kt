package com.geovault.tracker.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class CanonicalTimeNormalizerTest {
    @Test
    fun normalizeTimestampMs_convertsSecondsInputToMs() {
        val nowMs = 1_700_000_000_000L
        val timestampSeconds = 1_700_000_000L
        assertEquals(1_700_000_000_000L, CanonicalTimeNormalizer.normalizeTimestampMs(timestampSeconds, nowMs))
    }

    @Test
    fun normalizeTimestampMs_keepsMillisecondInput() {
        val nowMs = 1_700_000_000_000L
        val timestampMs = 1_700_000_123_456L
        assertEquals(timestampMs, CanonicalTimeNormalizer.normalizeTimestampMs(timestampMs, nowMs))
    }

    @Test
    fun normalizeTimestampMs_usesNowForMissingTimestamp() {
        val nowMs = 1_700_000_000_000L
        assertEquals(nowMs, CanonicalTimeNormalizer.normalizeTimestampMs(0L, nowMs))
        assertEquals(nowMs, CanonicalTimeNormalizer.normalizeTimestampMs(-42L, nowMs))
    }

    @Test
    fun ageMs_prefersElapsedRealtimeWhenAvailable() {
        val age = CanonicalTimeNormalizer.ageMs(
            nowMs = 100_000L,
            eventMs = 99_900L,
            nowElapsedRealtimeNanos = 6_000_000_000L,
            eventElapsedRealtimeNanos = 4_500_000_000L
        )
        assertEquals(1_500L, age)
    }

    @Test
    fun deltaSeconds_prefersElapsedRealtimeWhenAvailable() {
        val dtSeconds = CanonicalTimeNormalizer.deltaSeconds(
            previousTimestampMs = 1_000L,
            currentTimestampMs = 4_000L,
            previousElapsedRealtimeNanos = 5_000_000_000L,
            currentElapsedRealtimeNanos = 7_250_000_000L
        )
        assertEquals(2.25, dtSeconds, 0.0001)
    }
}
