package com.geovault.tracker.fragments.map

import org.junit.Assert.assertEquals
import org.junit.Test

class MapAccuracyRadiusPolicyTest {
    @Test
    fun resolveAccuracyRadiusMeters_prefersStreamedAccuracy() {
        val accuracy = MapAccuracyRadiusPolicy.resolveAccuracyRadiusMeters(
            AccuracyRadiusInput(
                streamedAccuracyMeters = 15f,
                trackingServiceAccuracyMeters = 120f,
                allowTrackingServiceFallback = true
            )
        )

        assertEquals(15.0, accuracy, 0.0)
    }

    @Test
    fun resolveAccuracyRadiusMeters_usesTrackingFallbackWhenAllowed() {
        val accuracy = MapAccuracyRadiusPolicy.resolveAccuracyRadiusMeters(
            AccuracyRadiusInput(
                streamedAccuracyMeters = null,
                trackingServiceAccuracyMeters = 45f,
                allowTrackingServiceFallback = true
            )
        )

        assertEquals(45.0, accuracy, 0.0)
    }

    @Test
    fun resolveAccuracyRadiusMeters_ignoresTrackingFallbackWhenDisallowed() {
        val accuracy = MapAccuracyRadiusPolicy.resolveAccuracyRadiusMeters(
            AccuracyRadiusInput(
                streamedAccuracyMeters = null,
                trackingServiceAccuracyMeters = 45f,
                allowTrackingServiceFallback = false
            )
        )

        assertEquals(0.0, accuracy, 0.0)
    }

    @Test
    fun resolveAccuracyRadiusMeters_rejectsInvalidAccuracyValues() {
        val accuracy = MapAccuracyRadiusPolicy.resolveAccuracyRadiusMeters(
            AccuracyRadiusInput(
                streamedAccuracyMeters = Float.NaN,
                trackingServiceAccuracyMeters = Float.POSITIVE_INFINITY,
                allowTrackingServiceFallback = true
            )
        )

        assertEquals(0.0, accuracy, 0.0)
    }
}
