package com.geovault.tracker.policy.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationFilterTest {

    @Test
    fun firstFix_isAcceptedVerbatim() {
        val filter = LocationFilter(LocationFilterConfig.Default)
        val result = filter.evaluate(
            LocationInput(
                latitude = 24.7097,
                longitude = -81.1011,
                timestampMs = 0L,
                accuracyMeters = 5f,
            )
        )
        assertEquals(LocationFilterResult.Decision.Accepted, result.decision)
    }

    @Test
    fun lowAccuracy_isRejectedBeforeFilterPipeline() {
        val filter = LocationFilter(LocationFilterConfig.Default.copy(trackingAccuracyThresholdMeters = 100.0))
        val result = filter.evaluate(
            LocationInput(
                latitude = 24.7097,
                longitude = -81.1011,
                timestampMs = 0L,
                accuracyMeters = 250f,
            )
        )
        assertEquals(LocationFilterResult.Decision.Rejected, result.decision)
        assertEquals("low-accuracy", result.reason)
    }

    @Test
    fun conservativePolicy_rejectsObviousTeleport() {
        val filter = LocationFilter(LocationFilterConfig.Default)
        filter.evaluate(
            LocationInput(
                latitude = 24.7097,
                longitude = -81.1011,
                timestampMs = 0L,
                accuracyMeters = 5f,
                speedMps = 1f,
            )
        )
        val result = filter.evaluate(
            LocationInput(
                latitude = 24.8500,
                longitude = -81.0000,
                timestampMs = 1_000L,
                accuracyMeters = 5f,
                speedMps = 1f,
            )
        )
        assertEquals(LocationFilterResult.Decision.Rejected, result.decision)
    }

    @Test
    fun adjustPolicy_clipsTeleportInsteadOfRejecting() {
        val filter = LocationFilter(
            LocationFilterConfig.Default.copy(policy = LocationFilterPolicy.Adjust)
        )
        filter.evaluate(
            LocationInput(
                latitude = 24.7097,
                longitude = -81.1011,
                timestampMs = 0L,
                accuracyMeters = 5f,
                speedMps = 1f,
            )
        )
        val result = filter.evaluate(
            LocationInput(
                latitude = 24.7300,
                longitude = -81.1011,
                timestampMs = 1_000L,
                accuracyMeters = 5f,
                speedMps = 1f,
            )
        )
        assertEquals(LocationFilterResult.Decision.Adjusted, result.decision)
        assertNotNull(result.adjustedLatitude)
        assertNotNull(result.adjustedLongitude)
    }

    @Test
    fun passThroughPolicy_neverModifiesGeometry() {
        val filter = LocationFilter(
            LocationFilterConfig.Default.copy(policy = LocationFilterPolicy.PassThrough)
        )
        filter.evaluate(
            LocationInput(
                latitude = 24.7097,
                longitude = -81.1011,
                timestampMs = 0L,
                accuracyMeters = 5f,
                speedMps = 1f,
            )
        )
        val result = filter.evaluate(
            LocationInput(
                latitude = 24.7300,
                longitude = -81.1011,
                timestampMs = 1_000L,
                accuracyMeters = 5f,
                speedMps = 1f,
            )
        )
        assertEquals(LocationFilterResult.Decision.Accepted, result.decision)
    }

    @Test
    fun stationaryWalk_acceptsSlowSteadyMotion() {
        val filter = LocationFilter(LocationFilterConfig.Default)
        var lat = 24.7097
        val lon = -81.1011
        var ts = 0L
        var lastDecision: LocationFilterResult? = null
        repeat(15) {
            lat += 0.000005
            ts += 1_000L
            lastDecision = filter.evaluate(
                LocationInput(
                    latitude = lat,
                    longitude = lon,
                    timestampMs = ts,
                    accuracyMeters = 6f,
                    speedMps = 0.6f,
                    bearingDegrees = 0f,
                )
            )
        }
        assertEquals(LocationFilterResult.Decision.Accepted, lastDecision?.decision)
    }

    @Test
    fun drivingBurst_passesThroughCleanly() {
        val filter = LocationFilter(LocationFilterConfig.Default)
        var lat = 24.7097
        var lon = -81.1011
        var ts = 0L
        repeat(3) {
            ts += 1_000L
            lat += 0.000150
            lon += 0.000050
            filter.evaluate(
                LocationInput(
                    latitude = lat,
                    longitude = lon,
                    timestampMs = ts,
                    accuracyMeters = 5f,
                    speedMps = 18f,
                    bearingDegrees = 45f,
                )
            )
        }
        var rejections = 0
        repeat(8) {
            ts += 1_000L
            lat += 0.000150
            lon += 0.000050
            val r = filter.evaluate(
                LocationInput(
                    latitude = lat,
                    longitude = lon,
                    timestampMs = ts,
                    accuracyMeters = 5f,
                    speedMps = 18f,
                    bearingDegrees = 45f,
                )
            )
            if (r.decision == LocationFilterResult.Decision.Rejected) rejections++
        }
        assertEquals(0, rejections)
    }

    @Test
    fun accurateHighwayMotion_withTinyReportedSpeed_isAccepted() {
        val filter = LocationFilter(LocationFilterConfig.Default)
        filter.evaluate(
            LocationInput(
                latitude = 24.7097,
                longitude = -81.1011,
                timestampMs = 0L,
                accuracyMeters = 3f,
                speedMps = 0.05f,
                bearingDegrees = 45f,
            )
        )

        val result = filter.evaluate(
            LocationInput(
                latitude = 24.7115,
                longitude = -81.1011,
                timestampMs = 8_000L,
                accuracyMeters = 3f,
                speedMps = 0.05f,
                bearingDegrees = 45f,
            )
        )

        assertEquals(LocationFilterResult.Decision.Accepted, result.decision)
    }

    @Test
    fun accurateHighwayMotion_withoutReportedSpeed_isAccepted() {
        val filter = LocationFilter(LocationFilterConfig.Default)
        filter.evaluate(
            LocationInput(
                latitude = 24.7097,
                longitude = -81.1011,
                timestampMs = 0L,
                accuracyMeters = 3f,
                speedMps = null,
                bearingDegrees = 45f,
            )
        )

        val result = filter.evaluate(
            LocationInput(
                latitude = 24.7115,
                longitude = -81.1011,
                timestampMs = 8_000L,
                accuracyMeters = 3f,
                speedMps = null,
                bearingDegrees = 45f,
            )
        )

        assertEquals(LocationFilterResult.Decision.Accepted, result.decision)
    }

    @Test
    fun conservativePolicy_suppressesStationaryJitterWithinAccuracyEnvelope() {
        // Realistic standstill noise: 8.9 m raw step with ~20 m accuracy
        // means RSS-corrected motion is zero -- the chipset is telling us
        // we did not move beyond uncertainty. The filter must snap to
        // the anchor instead of recording phantom drift.
        val filter = LocationFilter(LocationFilterConfig.Default)
        filter.evaluate(
            LocationInput(
                latitude = 24.7097,
                longitude = -81.1011,
                timestampMs = 0L,
                accuracyMeters = 20f,
                speedMps = 0f,
            )
        )

        val result = filter.evaluate(
            LocationInput(
                latitude = 24.70978,
                longitude = -81.1011,
                timestampMs = 1_000L,
                accuracyMeters = 20f,
                speedMps = 0f,
            )
        )

        assertEquals(LocationFilterResult.Decision.Adjusted, result.decision)
        assertEquals("uncertainty-suppressed", result.reason)
        assertEquals(24.7097, result.adjustedLatitude ?: 0.0, 0.0000001)
        assertEquals(-81.1011, result.adjustedLongitude ?: 0.0, 0.0000001)
    }

    @Test
    fun lowAccuracyMotion_withoutReportedSpeed_stillRejectsAnomaly() {
        val filter = LocationFilter(LocationFilterConfig.Default)
        filter.evaluate(
            LocationInput(
                latitude = 24.7097,
                longitude = -81.1011,
                timestampMs = 0L,
                accuracyMeters = 55f,
                speedMps = null,
                bearingDegrees = 45f,
            )
        )

        val result = filter.evaluate(
            LocationInput(
                latitude = 24.7115,
                longitude = -81.1011,
                timestampMs = 8_000L,
                accuracyMeters = 55f,
                speedMps = null,
                bearingDegrees = 45f,
            )
        )

        assertEquals(LocationFilterResult.Decision.Rejected, result.decision)
    }

    @Test
    fun phantomStepWhileSittingStill_isSnappedToAnchor_notAcceptedWithinCap() {
        // Reproduction of the field log: dt=20s, raw=~38m, accuracy=~30m,
        // reported speed = 0. Pre-fix, this was accepted as "within-cap"
        // because raw < (accuracy * 3). The tightened noisy-standstill
        // gate must trust the stationary classifier and snap to anchor.
        val filter = LocationFilter(LocationFilterConfig.Default)
        repeat(3) { i ->
            filter.evaluate(
                LocationInput(
                    latitude = 24.7097,
                    longitude = -81.1011,
                    timestampMs = (i * 1_000L),
                    accuracyMeters = 30f,
                    speedMps = 0f,
                    bearingDegrees = 45f,
                )
            )
        }

        val result = filter.evaluate(
            LocationInput(
                latitude = 24.71004,  // ~38 m north of the anchor
                longitude = -81.1011,
                timestampMs = 20_000L,
                accuracyMeters = 30f,
                speedMps = 0f,
                bearingDegrees = 45f,
            )
        )

        assertEquals(LocationFilterResult.Decision.Adjusted, result.decision)
        assertEquals("uncertainty-suppressed", result.reason)
    }

    @Test
    fun outlierBeyondOneAndAHalfCapCandidate_isRejectedAsOutlierCapped() {
        // Cap inflation (anchor trust, anomaly) puts the cap around 90 m
        // for accuracy=30 m. A 200 m raw step blows past the 1.5x cap
        // multiplier and must reject as outlier-capped, not get clipped.
        val filter = LocationFilter(LocationFilterConfig.Default)
        filter.evaluate(
            LocationInput(
                latitude = 24.7097,
                longitude = -81.1011,
                timestampMs = 0L,
                accuracyMeters = 30f,
                speedMps = 1f,
                bearingDegrees = 90f,
            )
        )

        val result = filter.evaluate(
            LocationInput(
                latitude = 24.7097,
                longitude = -81.0991,  // ~200 m east
                timestampMs = 5_000L,
                accuracyMeters = 30f,
                speedMps = 1f,
                bearingDegrees = 90f,
            )
        )

        assertEquals(LocationFilterResult.Decision.Rejected, result.decision)
        assertEquals("outlier-capped", result.reason)
    }

    @Test
    fun motionChangeReset_clearsAnchor() {
        val filter = LocationFilter(LocationFilterConfig.Default)
        filter.evaluate(
            LocationInput(
                latitude = 24.7097,
                longitude = -81.1011,
                timestampMs = 0L,
                accuracyMeters = 5f,
                speedMps = 0f,
            )
        )
        assertTrue(filter.lastAcceptedTimestampMs == 0L)
        filter.onMotionChanged()
        assertEquals(null, filter.lastAcceptedTimestampMs)
    }
}
