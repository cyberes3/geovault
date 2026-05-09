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
    fun accurateHighwayMotion_withTinyReportedSpeed_isRejectedAsAnomaly() {
        // TS-aligned behavior: a near-zero reported speed paired with a
        // ~25 m/s RSS-implied displacement is a clear chipset
        // misreport, and the implied/reported ratio (~50) puts the
        // anomaly score at 1.0. The filter must reject this outright
        // rather than smuggle it through as accurate motion.
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

        assertEquals(LocationFilterResult.Decision.Rejected, result.decision)
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
        // the anchor instead of recording phantom drift. Three priming
        // fixes match `tslocationmanager`'s minimum buffer for stationary
        // classification.
        val filter = LocationFilter(LocationFilterConfig.Default)
        repeat(3) { i ->
            filter.evaluate(
                LocationInput(
                    latitude = 24.7097,
                    longitude = -81.1011,
                    timestampMs = i * 1_000L,
                    accuracyMeters = 20f,
                    speedMps = 0f,
                )
            )
        }

        val result = filter.evaluate(
            LocationInput(
                latitude = 24.70978,
                longitude = -81.1011,
                timestampMs = 4_000L,
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
    fun phantomJumpInsideRawCloseEnvelope_isSnappedToAnchor() {
        // Field-log shape: raw~31.8 m, accuracy=26 m, dt=30 s. Path 2
        // (raw <= accuracy * 1.5 = 39 m) catches it: chipset reports
        // zero motion, displacement fits inside the uncertainty
        // envelope, so we snap to anchor instead of committing phantom
        // motion that would pollute the rolling buffer.
        val filter = LocationFilter(LocationFilterConfig.Default)
        repeat(3) { i ->
            filter.evaluate(
                LocationInput(
                    latitude = 24.7097,
                    longitude = -81.1011,
                    timestampMs = i * 1_000L,
                    accuracyMeters = 26f,
                    speedMps = 0f,
                    bearingDegrees = 90f,
                )
            )
        }

        // ~31 m east of the anchor at lat 24.7 (well inside 39 m envelope)
        val result = filter.evaluate(
            LocationInput(
                latitude = 24.7097,
                longitude = -81.10079,
                timestampMs = 30_000L,
                accuracyMeters = 26f,
                speedMps = 0f,
                bearingDegrees = 90f,
            )
        )

        assertEquals(LocationFilterResult.Decision.Adjusted, result.decision)
        assertEquals("uncertainty-suppressed", result.reason)
    }

    @Test
    fun severeImpliedAnomaly_isRejectedBeforeWithinCapAccept() {
        // Locks in the resolveConservative reorder: when implied
        // anomaly is >= 0.85 the fix must be rejected as
        // `severe-anomaly`, even if other paths (within-cap accept,
        // outlier-capped) would otherwise apply.
        //
        // computeImpliedAnomaly's speedScore saturates when
        // (RSS-corrected implied) / max(reported, 0.5) >= 4. With
        // accuracy=2 m, RSS = ~2.83 m. A 50 m raw step in 1 s gives
        // effective ~47 m and implied ~47 m/s. Against a reported
        // speed of 1 m/s the ratio is 47 -> speedScore = 1 -> anomaly
        // = 1, well above the 0.85 severe threshold. The reorder
        // ensures we reject before any within-cap accept could fire.
        val filter = LocationFilter(LocationFilterConfig.Default)
        filter.evaluate(
            LocationInput(
                latitude = 24.7097,
                longitude = -81.1011,
                timestampMs = 0L,
                accuracyMeters = 2f,
                speedMps = 1f,
                bearingDegrees = 0f,
            )
        )

        val result = filter.evaluate(
            LocationInput(
                latitude = 24.7097 + 0.000449,  // ~50 m north
                longitude = -81.1011,
                timestampMs = 1_000L,
                accuracyMeters = 2f,
                speedMps = 1f,
                bearingDegrees = 0f,
            )
        )

        assertEquals(LocationFilterResult.Decision.Rejected, result.decision)
        assertEquals("severe-anomaly", result.reason)
    }

    @Test
    fun outlierBeyondOneAndAHalfCapCandidate_isRejectedAsOutlierCapped() {
        // Reproduces the outlier-capped path under the new TS-aligned
        // ordering: severe-anomaly is checked first, so the inputs
        // must keep the implied/reported ratio below 3.625 while still
        // pushing raw above capCandidate * 1.5. With reported 15 m/s,
        // accuracy 5 m, dt 0.5 s, raw ~30 m the kinematic cap collapses
        // to 15 m (`canTrustImplied` is false at dt < 1 s), implied
        // works out to ~46 m/s (ratio 3.06 -> speedScore 0.62), and
        // raw 30 > cap * 1.5 = 22.5 fires the outlier path.
        val filter = LocationFilter(LocationFilterConfig.Default)
        filter.evaluate(
            LocationInput(
                latitude = 24.7097,
                longitude = -81.1011,
                timestampMs = 0L,
                accuracyMeters = 5f,
                speedMps = 15f,
                bearingDegrees = 0f,
            )
        )

        val result = filter.evaluate(
            LocationInput(
                latitude = 24.7097 + 0.000270,  // ~30 m north
                longitude = -81.1011,
                timestampMs = 500L,
                accuracyMeters = 5f,
                speedMps = 15f,
                bearingDegrees = 0f,
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
