package com.geovault.common.maps.location

import org.junit.Assert.assertEquals
import org.junit.Test

class AccuracyRadiusPolicyTest {
    @Test
    fun `uses streamed accuracy when valid`() {
        val result = AccuracyRadiusPolicy.resolveAccuracyRadiusMeters(
            AccuracyRadiusInput(
                streamedAccuracyMeters = 12.5f,
                fallbackAccuracyMeters = 45f,
                allowFallback = true,
            )
        )
        assertEquals(12.5, result, 0.0001)
    }

    @Test
    fun `uses fallback accuracy only when enabled`() {
        val enabled = AccuracyRadiusPolicy.resolveAccuracyRadiusMeters(
            AccuracyRadiusInput(
                streamedAccuracyMeters = null,
                fallbackAccuracyMeters = 30f,
                allowFallback = true,
            )
        )
        val disabled = AccuracyRadiusPolicy.resolveAccuracyRadiusMeters(
            AccuracyRadiusInput(
                streamedAccuracyMeters = null,
                fallbackAccuracyMeters = 30f,
                allowFallback = false,
            )
        )
        assertEquals(30.0, enabled, 0.0001)
        assertEquals(0.0, disabled, 0.0001)
    }

    @Test
    fun `invalid values resolve to zero`() {
        val result = AccuracyRadiusPolicy.resolveAccuracyRadiusMeters(
            AccuracyRadiusInput(
                streamedAccuracyMeters = -1f,
                fallbackAccuracyMeters = Float.NaN,
                allowFallback = true,
            )
        )
        assertEquals(0.0, result, 0.0001)
    }
}
