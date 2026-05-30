package com.geovault.tracker.location

import com.geovault.tracker.services.TrackingMotionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTrackingMotionEngineTest {

    @Test
    fun acceptedFix_singleHighSpeedSample_doesNotFlipFromWalking() {
        // A single phantom high-speed sample on a walking baseline must
        // not promote to BIKING. Two consecutive samples above the
        // upper threshold are required.
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        // Establish a 1.2 m/s walking baseline.
        repeat(5) {
            engine.onAcceptedFix(speedMps = 1.2f, eventTimeMs = (it + 1) * 1_000L)
        }
        assertEquals(TrackingMotionMode.WALKING, engine.snapshot().mode)

        // One phantom 8 m/s burst.
        val output = engine.onAcceptedFix(speedMps = 8f, eventTimeMs = 6_000L)
        assertEquals(TrackingMotionMode.WALKING, output.state.mode)
    }

    @Test
    fun acceptedFix_twoConsecutiveHighSpeedSamples_promotesWalkingToBiking() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        repeat(5) {
            engine.onAcceptedFix(speedMps = 1.2f, eventTimeMs = (it + 1) * 1_000L)
        }
        // Need two consecutive samples whose smoothed value exceeds 2.5 m/s.
        // Use 12 m/s bursts so even with 0.30 alpha smoothing the smoothed
        // crosses the threshold on the first sample (0.7*1.2 + 0.3*12 ~ 4.4).
        engine.onAcceptedFix(speedMps = 12f, eventTimeMs = 6_000L)
        assertEquals(TrackingMotionMode.WALKING, engine.snapshot().mode)
        val out = engine.onAcceptedFix(speedMps = 12f, eventTimeMs = 7_000L)
        assertEquals(TrackingMotionMode.BIKING, out.state.mode)
    }

    @Test
    fun rejectedFix_doesNotMutateSmoothedSpeed() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        engine.onAcceptedFix(speedMps = 1.2f, eventTimeMs = 1_000L)
        val before = engine.snapshot().smoothedSpeedMps
        // Old API used to feed a speed hint here; new contract just
        // bumps lastEvidenceAtMs.
        val out = engine.onRejectedFix(eventTimeMs = 2_000L)
        assertEquals(before, out.state.smoothedSpeedMps, 0.0001f)
        assertEquals(2_000L, out.state.lastEvidenceAtMs)
    }

    @Test
    fun rejectedFix_clearsPendingPromotionStreak() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        engine.onMotionEvidence(
            speedMps = 12f,
            eventTimeMs = 1_000L,
            confidence = AutoTrackingMotionEvidenceConfidence.High,
        )
        assertEquals(TrackingMotionMode.WALKING, engine.snapshot().mode)

        engine.onRejectedFix(eventTimeMs = 2_000L)
        val out = engine.onMotionEvidence(
            speedMps = 12f,
            eventTimeMs = 3_000L,
            confidence = AutoTrackingMotionEvidenceConfidence.High,
        )

        assertEquals(TrackingMotionMode.WALKING, out.state.mode)
    }

    @Test
    fun motionEvidence_twoHighConfidenceSamples_promotesWalkingToBiking() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)

        engine.onMotionEvidence(
            speedMps = 6f,
            eventTimeMs = 1_000L,
            confidence = AutoTrackingMotionEvidenceConfidence.High,
        )
        val out = engine.onMotionEvidence(
            speedMps = 6f,
            eventTimeMs = 2_000L,
            confidence = AutoTrackingMotionEvidenceConfidence.High,
        )

        assertEquals(TrackingMotionMode.BIKING, out.state.mode)
    }

    @Test
    fun demotion_isSingleSample() {
        // We want to drop to lower-power tracking quickly when motion
        // stops. Demotion is single-sample below the lower bound.
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        // Force-promote to BIKING via two high-speed accepted samples.
        engine.onAcceptedFix(speedMps = 12f, eventTimeMs = 1_000L)
        engine.onAcceptedFix(speedMps = 12f, eventTimeMs = 2_000L)
        assertEquals(TrackingMotionMode.BIKING, engine.snapshot().mode)

        // A single low sample drives the smoothed speed below the 1.2
        // lower bound and demotes.
        repeat(20) { engine.onAcceptedFix(speedMps = 0.0f, eventTimeMs = (it + 3) * 1_000L) }
        assertEquals(TrackingMotionMode.WALKING, engine.snapshot().mode)
    }

    @Test
    fun pauseResume_keepsMode_and_updatesPauseFlag() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 1_000L)
        val paused = engine.onGpsPaused(nowMs = 2_000L)
        assertTrue(paused.state.isGpsPaused)
        val resumed = engine.onGpsResumed(nowMs = 3_000L)
        assertFalse(resumed.state.isGpsPaused)
        assertEquals(paused.state.mode, resumed.state.mode)
    }

    @Test
    fun acceptedFix_twoSamplesWellAboveBikingUpper_skipsDirectlyToDriving() {
        // When the observed speed is clearly highway-class (well above
        // 1.5 * BIKING_TO_DRIVING_UPPER_MPS = 13.5 m/s), the WALKING->BIKING
        // intermediate is wasted cadence. Two samples at 22 m/s skip
        // straight to DRIVING.
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        engine.onAcceptedFix(speedMps = 22f, eventTimeMs = 1_000L)
        // First sample only arms the streak.
        assertEquals(TrackingMotionMode.WALKING, engine.snapshot().mode)
        val out = engine.onAcceptedFix(speedMps = 22f, eventTimeMs = 2_000L)
        assertEquals(TrackingMotionMode.DRIVING, out.state.mode)
        assertEquals(TransitionPath.SKIP_TO_DRIVING, out.transitionPath)
    }

    @Test
    fun acceptedFix_borderlineBikingSpeed_doesNotSkipToDriving() {
        // Bursts around 6 m/s are bicycling-class. The skip only kicks in
        // strictly above 13.5 m/s; 6 m/s must still go through BIKING.
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        engine.onAcceptedFix(speedMps = 6f, eventTimeMs = 1_000L)
        val out = engine.onAcceptedFix(speedMps = 6f, eventTimeMs = 2_000L)
        assertEquals(TrackingMotionMode.BIKING, out.state.mode)
        assertEquals(TransitionPath.LADDER, out.transitionPath)
    }

    @Test
    fun acceptedFix_observedSpeedAboveEma_drivesPromotionDecision() {
        // A 22 m/s observation has an EMA contribution of 6.6 m/s on the
        // first sample (0.30 alpha over a 0 baseline). The decision speed
        // should use max(EMA, observed) so the promotion path sees 22 m/s,
        // not 6.6 m/s. Two such samples therefore reach DRIVING via the
        // skip even though the EMA never crosses 13.5 m/s on sample 1.
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        engine.onAcceptedFix(speedMps = 22f, eventTimeMs = 1_000L)
        val out = engine.onAcceptedFix(speedMps = 22f, eventTimeMs = 2_000L)
        assertEquals(TrackingMotionMode.DRIVING, out.state.mode)
    }

    @Test
    fun acceptedFix_twoDrivingSpeedSamples_promotesBikingToDriving() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        engine.onAcceptedFix(speedMps = 6f, eventTimeMs = 1_000L)
        engine.onAcceptedFix(speedMps = 6f, eventTimeMs = 2_000L)
        assertEquals(TrackingMotionMode.BIKING, engine.snapshot().mode)

        engine.onAcceptedFix(speedMps = 12f, eventTimeMs = 3_000L)
        assertEquals(TrackingMotionMode.BIKING, engine.snapshot().mode)
        val out = engine.onAcceptedFix(speedMps = 12f, eventTimeMs = 4_000L)

        assertEquals(TrackingMotionMode.DRIVING, out.state.mode)
        assertEquals(TransitionPath.LADDER, out.transitionPath)
    }

    @Test
    fun motionEvidence_drivingClassSpeedFromWalking_skipsDirectlyToDriving() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)

        engine.onMotionEvidence(
            speedMps = 12f,
            eventTimeMs = 1_000L,
            confidence = AutoTrackingMotionEvidenceConfidence.High,
        )
        val out = engine.onMotionEvidence(
            speedMps = 12f,
            eventTimeMs = 2_000L,
            confidence = AutoTrackingMotionEvidenceConfidence.High,
        )

        assertEquals(TrackingMotionMode.DRIVING, out.state.mode)
        assertEquals(TransitionPath.SKIP_TO_DRIVING, out.transitionPath)
    }

    @Test
    fun motionEvidence_drivingClassSpeedFromBiking_promotesImmediatelyToDriving() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        engine.onAcceptedFix(speedMps = 6f, eventTimeMs = 1_000L)
        engine.onAcceptedFix(speedMps = 6f, eventTimeMs = 2_000L)
        assertEquals(TrackingMotionMode.BIKING, engine.snapshot().mode)

        val out = engine.onMotionEvidence(
            speedMps = 12f,
            eventTimeMs = 3_000L,
            confidence = AutoTrackingMotionEvidenceConfidence.High,
        )

        assertEquals(TrackingMotionMode.DRIVING, out.state.mode)
        assertEquals(TransitionPath.SKIP_TO_DRIVING, out.transitionPath)
    }

    @Test
    fun periodicTick_decays_speed() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        // Two consecutive 8 m/s accepted fixes promote to BIKING and lift
        // the smoothed speed well above 0.
        engine.onAcceptedFix(speedMps = 8f, eventTimeMs = 1_000L)
        engine.onAcceptedFix(speedMps = 8f, eventTimeMs = 2_000L)
        val before = engine.snapshot().smoothedSpeedMps
        assertTrue(before > 0f)
        engine.onTick(nowMs = 200_000L)
        val after = engine.snapshot().smoothedSpeedMps
        assertTrue(after < before)
    }

    @Test
    fun periodicTick_doesNotPromoteWalkingToBiking() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        engine.onAcceptedFix(speedMps = 8f, eventTimeMs = 1_000L)

        val out = engine.onTick(nowMs = 200_000L)

        assertFalse(out.modeChanged)
        assertEquals(TransitionPath.NONE, out.transitionPath)
        assertEquals(TrackingMotionMode.WALKING, out.state.mode)
    }

    @Test
    fun periodicTick_doesNotDemoteDriving() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        engine.onAcceptedFix(speedMps = 22f, eventTimeMs = 1_000L)
        engine.onAcceptedFix(speedMps = 22f, eventTimeMs = 2_000L)
        assertEquals(TrackingMotionMode.DRIVING, engine.snapshot().mode)

        val out = engine.onTick(nowMs = 300_000L)

        assertFalse(out.modeChanged)
        assertEquals(TransitionPath.NONE, out.transitionPath)
        assertEquals(TrackingMotionMode.DRIVING, out.state.mode)
    }
}
