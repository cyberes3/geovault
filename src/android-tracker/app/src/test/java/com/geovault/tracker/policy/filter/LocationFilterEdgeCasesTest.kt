package com.geovault.tracker.policy.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationFilterEdgeCasesTest {

    @Test
    fun adjustPolicy_clipScalesAlongLineFromAnchor() {
        // Verify the clip-to-cap interpolation actually places the adjusted
        // point on the line between previous and candidate.
        val filter = LocationFilter(
            LocationFilterConfig.Default.copy(policy = LocationFilterPolicy.Adjust)
        )
        filter.evaluate(
            LocationInput(
                latitude = 10.0,
                longitude = 20.0,
                timestampMs = 0L,
                accuracyMeters = 5f,
                speedMps = 1f,
            )
        )
        val r = filter.evaluate(
            LocationInput(
                latitude = 10.05,
                longitude = 20.05,
                timestampMs = 1_000L,
                accuracyMeters = 5f,
                speedMps = 1f,
            )
        )
        assertEquals(LocationFilterResult.Decision.Adjusted, r.decision)
        val adjLat = requireNotNull(r.adjustedLatitude)
        val adjLon = requireNotNull(r.adjustedLongitude)
        // Adjusted point sits on the segment 10..10.05 in lat and 20..20.05 in lon.
        assertTrue("adjusted lat must be between anchor and raw", adjLat in 10.0..10.05)
        assertTrue("adjusted lon must be between anchor and raw", adjLon in 20.0..20.05)
        // Latitude / longitude offsets share the same scale (line interpolation).
        val latProgress = (adjLat - 10.0) / 0.05
        val lonProgress = (adjLon - 20.0) / 0.05
        assertEquals(latProgress, lonProgress, 1e-6)
    }

    @Test
    fun speedCap_passThroughPolicy_neverClipsOrRejects() {
        val filter = LocationFilter(
            LocationFilterConfig.Default.copy(
                policy = LocationFilterPolicy.PassThrough,
                maxImpliedSpeedMps = 10.0,
            )
        )
        filter.evaluate(
            LocationInput(
                latitude = 0.0,
                longitude = 0.0,
                timestampMs = 0L,
                accuracyMeters = 5f,
                speedMps = 1f,
            )
        )
        val r = filter.evaluate(
            LocationInput(
                latitude = 0.001,
                longitude = 0.001,
                timestampMs = 1_000L,
                accuracyMeters = 5f,
                speedMps = 1f,
            )
        )
        assertEquals(LocationFilterResult.Decision.Accepted, r.decision)
    }

    @Test
    fun lowAccuracy_doesNotPollutePreviousAnchor() {
        // A low-accuracy reject after an accepted fix must leave the anchor
        // pointing at the *good* fix, not the rejected one.
        val filter = LocationFilter(
            LocationFilterConfig.Default.copy(trackingAccuracyThresholdMeters = 30.0)
        )
        filter.evaluate(
            LocationInput(
                latitude = 0.0,
                longitude = 0.0,
                timestampMs = 0L,
                accuracyMeters = 5f,
            )
        )
        val anchorBefore = filter.lastAcceptedLatLon
        val rejected = filter.evaluate(
            LocationInput(
                latitude = 1.0,
                longitude = 1.0,
                timestampMs = 1_000L,
                accuracyMeters = 80f,
            )
        )
        assertEquals(LocationFilterResult.Decision.Rejected, rejected.decision)
        assertEquals(anchorBefore, filter.lastAcceptedLatLon)
    }

    @Test
    fun firstFix_withZeroAccuracy_isAccepted() {
        // accuracyMeters absent (null) must not trip the accuracy gate.
        val filter = LocationFilter(LocationFilterConfig.Default)
        val r = filter.evaluate(
            LocationInput(
                latitude = 1.0,
                longitude = 1.0,
                timestampMs = 0L,
                accuracyMeters = null,
            )
        )
        assertEquals(LocationFilterResult.Decision.Accepted, r.decision)
    }
}
