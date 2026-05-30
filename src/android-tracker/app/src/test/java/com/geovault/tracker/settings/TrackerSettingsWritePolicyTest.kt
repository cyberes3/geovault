package com.geovault.tracker.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackerSettingsWritePolicyTest {

    private val policy = TrackerSettingsWritePolicy()

    @Test
    fun sanitize_clampsOutOfRangeValues() {
        val raw = TrackerSettings(
            accuracyFilterMeters = 50_000f,
            lowAccuracyFallbackTimeoutSec = 99_999L,
        )
        val s = policy.sanitize(raw)
        assertEquals(TrackerSettings.MAX_ACCURACY_FILTER_METERS, s.accuracyFilterMeters, 0.0001f)
        assertEquals(TrackerSettings.MAX_LOW_ACCURACY_FALLBACK_TIMEOUT_SEC, s.lowAccuracyFallbackTimeoutSec)
    }

    @Test
    fun sanitize_preservesNormalValues() {
        val base = TrackerSettings(
            accuracyFilterMeters = 17.5f,
            lowAccuracyFallbackTimeoutSec = 90L,
        )
        val next = policy.sanitize(base)
        assertEquals(17.5f, next.accuracyFilterMeters, 0.001f)
        assertEquals(90L, next.lowAccuracyFallbackTimeoutSec)
    }

}
