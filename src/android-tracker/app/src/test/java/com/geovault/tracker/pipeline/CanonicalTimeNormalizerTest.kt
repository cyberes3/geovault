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
}
