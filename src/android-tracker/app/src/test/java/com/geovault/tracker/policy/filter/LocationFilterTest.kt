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
    fun briskWalk_acceptsSteadyMotion() {
        // Per-fix progress (~7 m) clears both `2 * accuracy` (linear-
        // sum envelope) and `accuracy * 1.5` (raw-close envelope), and
        // reported speed (~7 m/s) is above the standstill motion floor.
        // Sub-floor walking is snapped by design (Phase 2 raised the
        // floor to 1.0 m/s to absorb chipset speed noise); the regime
        // covered here is true motion that should commit verbatim.
        val filter = LocationFilter(LocationFilterConfig.Default)
        var lat = 24.7097
        val lon = -81.1011
        var ts = 0L
        var lastDecision: LocationFilterResult? = null
        repeat(15) {
            lat += 0.00006
            ts += 1_000L
            lastDecision = filter.evaluate(
                LocationInput(
                    latitude = lat,
                    longitude = lon,
                    timestampMs = ts,
                    accuracyMeters = 3f,
                    speedMps = 7f,
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
        // The chipset reporting near-zero ground speed while the
        // device actually moved ~200 m in 8 s is just a sparse-fix
        // highway hop. `raw / dt = 25 m/s < 60` and `raw 200 m < 300
        // m`, so impliedAnomaly is false and the fix commits via
        // within-cap rather than being rejected. Pre-Phase-4 the
        // implied/reported ratio score would have rejected this as
        // severe-anomaly.
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
        // the anchor instead of recording phantom drift. Three priming
        // fixes satisfy the stationary classifier's minimum buffer.
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
    fun moderateAccuracyMotion_withoutReportedSpeed_isAcceptedWithinCap() {
        // 55 m accuracy is below the 100 m tracking threshold, raw is
        // 200 m at dt 8 s (raw/dt = 25 m/s < 60 maxImpliedSpeed and
        // raw < 300 maxBurst), so the implied-anomaly boolean is
        // false and the fix commits via within-cap. Pre-Phase-4 a
        // saturating burst score would have rejected this.
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

        assertEquals(LocationFilterResult.Decision.Accepted, result.decision)
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
    fun rawImpliedSpeedAboveCeiling_isRejectedAsImpliedSpeed() {
        // When raw/dt exceeds maxImpliedSpeed the boolean
        // impliedAnomaly fires and the outlier-capped reject path
        // renames to `implied-speed`. Pre-Phase-4 this used the
        // continuous severe-anomaly score.
        //
        // Threading the needle: raw must overshoot the inflated cap
        // (cap*1.5*1.5) while keeping RSS-corrected `impliedSpeedMps`
        // below the speed ceiling so the speed-spike branch does not
        // fire first. With acc=5 m, dt=0.5 s, reported=0, raw=35 m:
        //   raw/dt = 70 m/s > 60                  -> anomaly TRUE
        //   effective = 35 - sqrt(50) ~ 27.9 m
        //   impliedSpeedMps = 27.9/0.5 ~ 55.9 m/s -> no speed-spike
        //   canTrustImplied false (dt < 1) -> kinCap = 0
        //   accCap = 5*3 = 15 -> cap = 15 (rolling, kin floors at 5)
        //   inflated cap = 15*1.5 = 22.5; outlier @ 22.5*1.5 = 33.75
        //   raw 35 > 33.75 -> isOutlier true -> reason `implied-speed`.
        val filter = LocationFilter(LocationFilterConfig.Default)
        filter.evaluate(
            LocationInput(
                latitude = 24.7097,
                longitude = -81.1011,
                timestampMs = 0L,
                accuracyMeters = 5f,
                speedMps = 0f,
                bearingDegrees = 0f,
            )
        )

        val result = filter.evaluate(
            LocationInput(
                latitude = 24.7097 + 0.000315,  // ~35 m north
                longitude = -81.1011,
                timestampMs = 500L,
                accuracyMeters = 5f,
                speedMps = 0f,
                bearingDegrees = 0f,
            )
        )

        assertEquals(LocationFilterResult.Decision.Rejected, result.decision)
        assertEquals("implied-speed", result.reason)
    }

    @Test
    fun outlierBeyondOneAndAHalfCapCandidate_isRejectedAsOutlierCapped() {
        // Slow-but-far jump: raw/dt < 60 (no implied-speed anomaly),
        // raw < 300 (no burst anomaly), but raw still overshoots
        // capCandidate * 1.5. With acc=5, dt=0.5, reported=8 the kin
        // term is 8 m (canTrustImplied is false at dt<1) and accCap is
        // 15 -> capCandidate = 15. raw 24 > 22.5 -> isOutlier true,
        // reason `outlier-capped` (anomaly false).
        val filter = LocationFilter(LocationFilterConfig.Default)
        filter.evaluate(
            LocationInput(
                latitude = 24.7097,
                longitude = -81.1011,
                timestampMs = 0L,
                accuracyMeters = 5f,
                speedMps = 8f,
                bearingDegrees = 0f,
            )
        )

        val result = filter.evaluate(
            LocationInput(
                latitude = 24.7097 + 0.000216,  // ~24 m north
                longitude = -81.1011,
                timestampMs = 500L,
                accuracyMeters = 5f,
                speedMps = 8f,
                bearingDegrees = 0f,
            )
        )

        assertEquals(LocationFilterResult.Decision.Rejected, result.decision)
        assertEquals("outlier-capped", result.reason)
    }

    @Test
    fun linearSumDrift_isSnappedToAnchor() {
        // Old GeoVault tracker's `effective <= 0` rule: with prev acc
        // 20 m and curr acc 20 m the linear-sum envelope is 40 m. A
        // 35 m phantom drift fits inside the envelope but does NOT fit
        // inside path 2's `currAcc * 1.5 = 30 m` envelope, so this test
        // specifically locks in path 0.
        val filter = LocationFilter(LocationFilterConfig.Default)
        filter.evaluate(
            LocationInput(
                latitude = 24.7097,
                longitude = -81.1011,
                timestampMs = 0L,
                accuracyMeters = 20f,
                speedMps = 0.7f,
            )
        )

        val result = filter.evaluate(
            LocationInput(
                latitude = 24.7097 + 0.000314,  // ~35 m north
                longitude = -81.1011,
                timestampMs = 1_000L,
                accuracyMeters = 20f,
                speedMps = 0.7f,
            )
        )

        assertEquals(LocationFilterResult.Decision.Adjusted, result.decision)
        assertEquals("uncertainty-suppressed", result.reason)
    }

    @Test
    fun chipsetSpeedNoiseBelowOne_doesNotBypassSnap() {
        // Pre-fix the speed floor was 0.5 m/s, so a chipset reporting
        // 0.8 m/s during a true standstill would early-exit
        // `isNoisyStandstill` and commit the phantom motion. After
        // raising the floor to 1.0 the same noise band is captured.
        val filter = LocationFilter(LocationFilterConfig.Default)
        filter.evaluate(
            LocationInput(
                latitude = 24.7097,
                longitude = -81.1011,
                timestampMs = 0L,
                accuracyMeters = 15f,
                speedMps = 0.8f,
            )
        )

        val result = filter.evaluate(
            LocationInput(
                latitude = 24.7097 + 0.000180,  // ~20 m north (inside 30 m linear envelope)
                longitude = -81.1011,
                timestampMs = 1_000L,
                accuracyMeters = 15f,
                speedMps = 0.8f,
            )
        )

        assertEquals(LocationFilterResult.Decision.Adjusted, result.decision)
        assertEquals("uncertainty-suppressed", result.reason)
    }

    @Test
    fun legitimateDrivingFixAt15SecondGap_isAccepted() {
        // Field-log shape (2026-05-09 10:24-10:27): the chipset
        // delivered driving fixes at 10-25 s intervals, raw 250-450 m,
        // implied 13-16 m/s. Pre-Phase-4 the saturating burst score
        // rejected every one of these as `severe-anomaly`. New
        // boolean: raw/dt = 23 m/s < 60 maxImpliedSpeed AND
        // (raw 350 > 300 maxBurst, but dt 15 > 10 burstWindow ->
        // burst term false). impliedAnomaly = false -> accept.
        val filter = LocationFilter(LocationFilterConfig.Default)
        filter.evaluate(
            LocationInput(
                latitude = 24.7097,
                longitude = -81.1011,
                timestampMs = 0L,
                accuracyMeters = 5f,
                speedMps = 23f,
                bearingDegrees = 0f,
            )
        )

        val result = filter.evaluate(
            LocationInput(
                latitude = 24.7097 + 0.003150, // ~350 m north
                longitude = -81.1011,
                timestampMs = 15_000L,
                accuracyMeters = 5f,
                speedMps = 23f,
                bearingDegrees = 0f,
            )
        )

        assertEquals(LocationFilterResult.Decision.Accepted, result.decision)
        assertEquals("within-cap", result.reason)
    }

    @Test
    fun sparseDrivingStream_neverFalseRejects() {
        // 30 consecutive driving fixes at 12-22 s spacing and 250-500 m
        // per hop. None should be rejected -- this is the exact pattern
        // that pre-Phase-4 rejected as severe-anomaly across an entire
        // commute.
        val filter = LocationFilter(LocationFilterConfig.Default)
        val lon = -81.1011
        var lat = 24.7097
        var ts = 0L
        filter.evaluate(
            LocationInput(
                latitude = lat,
                longitude = lon,
                timestampMs = ts,
                accuracyMeters = 6f,
                speedMps = 22f,
                bearingDegrees = 0f,
            )
        )

        var rejects = 0
        repeat(30) { idx ->
            val dtSec = 12 + (idx % 6) * 2 // 12, 14, 16, 18, 20, 22
            val deltaMeters = dtSec * 22.0 // ~22 m/s = ~80 km/h
            ts += dtSec * 1_000L
            lat += deltaMeters / 111_000.0
            val r = filter.evaluate(
                LocationInput(
                    latitude = lat,
                    longitude = lon,
                    timestampMs = ts,
                    accuracyMeters = 6f,
                    speedMps = 22f,
                    bearingDegrees = 0f,
                )
            )
            if (r.decision == LocationFilterResult.Decision.Rejected) rejects++
        }
        assertEquals(0, rejects)
    }

    @Test
    fun lowAccuracyFix_doesNotPoisonAnomalyReference() {
        // The accuracy gate must not advance `lastSeenFix`: a 24 km
        // accuracy network-fallback fix is not a usable reference for
        // the next frame's anomaly calculation. The third fix below
        // would be rejected as severe-anomaly if `lastSeenFix` had
        // been clobbered by the garbage middle fix.
        val filter = LocationFilter(LocationFilterConfig.Default)
        val first = filter.evaluate(
            LocationInput(
                latitude = 24.7097,
                longitude = -81.1011,
                timestampMs = 0L,
                accuracyMeters = 5f,
                speedMps = 25f,
                bearingDegrees = 0f,
            )
        )
        assertEquals(LocationFilterResult.Decision.Accepted, first.decision)

        val garbage = filter.evaluate(
            LocationInput(
                latitude = 25.5000,  // ~88 km north of the anchor
                longitude = -81.1011,
                timestampMs = 1_000L,
                accuracyMeters = 24_000f,
                speedMps = 0f,
                bearingDegrees = 0f,
            )
        )
        assertEquals(LocationFilterResult.Decision.Rejected, garbage.decision)
        assertEquals("low-accuracy", garbage.reason)

        val recovered = filter.evaluate(
            LocationInput(
                latitude = 24.7097 + 2 * 0.000225, // ~50 m north of anchor
                longitude = -81.1011,
                timestampMs = 2_000L,
                accuracyMeters = 5f,
                speedMps = 25f,
                bearingDegrees = 0f,
            )
        )
        assertEquals(LocationFilterResult.Decision.Accepted, recovered.decision)
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

    /**
     * Resume-from-pause scenario: after a long stationary window the device
     * starts driving and the next fix lands hundreds of meters away. With
     * `onMotionChanged()` invoked on resume, the stale anchor is dropped so
     * the post-resume fix takes the first-fix path and is accepted verbatim
     * instead of being capped/rejected against a 30-minute-old position.
     */
    @Test
    fun motionChangeReset_postResumeFixTakesFirstFixPath() {
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
        assertEquals(0L, filter.lastAcceptedTimestampMs)

        filter.onMotionChanged()

        // ~530m east of the pre-pause anchor; would be hard-rejected as a
        // teleport without the motion-change reset.
        val postResume = filter.evaluate(
            LocationInput(
                latitude = 24.7097,
                longitude = -81.0959,
                timestampMs = 30L * 60L * 1000L,
                accuracyMeters = 8f,
                speedMps = 15f,
            )
        )
        assertEquals(LocationFilterResult.Decision.Accepted, postResume.decision)
        assertEquals(30L * 60L * 1000L, filter.lastAcceptedTimestampMs)
    }

    /**
     * First-fix bypasses the Kalman smoother: the very first observation
     * is accepted verbatim because [LocationFilter] short-circuits to
     * `commitAccept` before [smoothDecisionDistance] runs. Otherwise the
     * smoother would be initialised on a single arbitrary reading.
     */
    @Test
    fun firstFix_bypassesKalmanSmoothing() {
        val filter = LocationFilter(LocationFilterConfig.Default.copy(useKalman = true))
        val first = filter.evaluate(
            LocationInput(
                latitude = 24.7097,
                longitude = -81.1011,
                timestampMs = 0L,
                accuracyMeters = 5f,
                speedMps = 1f,
            )
        )
        assertEquals(LocationFilterResult.Decision.Accepted, first.decision)
        assertEquals("first-fix", first.reason)
    }

    /**
     * A true teleport still trips the 1.5x outlier reject because outlier
     * detection runs on raw distance, not smoothed. Kalman only damps the
     * within-cap comparison; it cannot rescue a fix that physically
     * jumped 200 m in 1 second past a 50 m cap.
     */
    @Test
    fun trueTeleport_stillRejectedDespiteKalman() {
        val filter = LocationFilter(LocationFilterConfig.Default.copy(useKalman = true))
        var lat = 24.7097
        val lon = -81.1011
        var ts = 0L
        repeat(5) {
            filter.evaluate(
                LocationInput(
                    latitude = lat,
                    longitude = lon,
                    timestampMs = ts,
                    accuracyMeters = 5f,
                    speedMps = 1f,
                    bearingDegrees = 0f,
                )
            )
            lat += 0.00001
            ts += 1_000L
        }
        val teleport = filter.evaluate(
            LocationInput(
                latitude = 24.8500,
                longitude = -81.0500,
                timestampMs = ts,
                accuracyMeters = 5f,
                speedMps = 1f,
                bearingDegrees = 0f,
            )
        )
        assertEquals(LocationFilterResult.Decision.Rejected, teleport.decision)
    }

    /**
     * Kalman smoothing: after a series of small steady observations a
     * single magnitude-spike fix sits just over the cap on raw effective
     * distance but well under once smoothed against the prior. The Adjust
     * policy must accept it as `within-cap` rather than clipping.
     *
     * Seeds the smoother with steady ~2 m hops so the predicted state
     * is small, then sends a borderline ~28 m fix against a typical cap
     * of ~30 m -- but with the kinematic cap shrunk by low speed so the
     * raw is borderline-above. The smoothed observation sits well below.
     */
    @Test
    fun kalmanSmoothing_rescuesBorderlineMagnitudeSpike() {
        val filter = LocationFilter(
            LocationFilterConfig.Default.copy(
                policy = LocationFilterPolicy.Adjust,
                useKalman = true,
            )
        )
        var lat = 24.7097
        val lon = -81.1011
        var ts = 0L
        // 6 small steady-state hops to seed Kalman state low.
        repeat(6) {
            filter.evaluate(
                LocationInput(
                    latitude = lat,
                    longitude = lon,
                    timestampMs = ts,
                    accuracyMeters = 6f,
                    speedMps = 0.5f,
                    bearingDegrees = 0f,
                )
            )
            lat += 0.0000180
            ts += 5_000L
        }
        val borderline = filter.evaluate(
            LocationInput(
                latitude = lat + 0.00027,
                longitude = lon,
                timestampMs = ts + 5_000L,
                accuracyMeters = 6f,
                speedMps = 0.5f,
                bearingDegrees = 0f,
            )
        )
        assertEquals(LocationFilterResult.Decision.Accepted, borderline.decision)
    }
}
