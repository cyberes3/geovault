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
        // Rejected fixes do not change smoothed speed; they only bump
        // lastEvidenceAtMs.
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
    fun demotion_singleLowSample_doesNotDemoteBikingToWalking() {
        // A single sample below the lower bound is not enough to demote;
        // the demotion streak guard requires three consecutive samples.
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        engine.onAcceptedFix(speedMps = 12f, eventTimeMs = 1_000L)
        engine.onAcceptedFix(speedMps = 12f, eventTimeMs = 2_000L)
        assertEquals(TrackingMotionMode.BIKING, engine.snapshot().mode)
        // Decay EMA to near-zero so the next accepted fix drives the
        // decision speed below BIKING_TO_WALKING_LOWER_MPS (1.2 m/s).
        engine.onTick(nowMs = 300_000L)

        val out = engine.onAcceptedFix(speedMps = 0.0f, eventTimeMs = 300_001L)
        assertEquals(TrackingMotionMode.BIKING, out.state.mode)
        assertFalse(out.modeChanged)
        assertEquals(1, out.state.consecutiveBelowLower)
    }

    @Test
    fun demotion_requiresThreeConsecutiveSamples_bikingToWalking() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        engine.onAcceptedFix(speedMps = 12f, eventTimeMs = 1_000L)
        engine.onAcceptedFix(speedMps = 12f, eventTimeMs = 2_000L)
        assertEquals(TrackingMotionMode.BIKING, engine.snapshot().mode)
        engine.onTick(nowMs = 300_000L)

        // First below-lower: streak=1, still BIKING.
        engine.onAcceptedFix(speedMps = 0.0f, eventTimeMs = 300_001L)
        assertEquals(TrackingMotionMode.BIKING, engine.snapshot().mode)

        // Second consecutive: streak=2, still BIKING (threshold is 3).
        engine.onAcceptedFix(speedMps = 0.0f, eventTimeMs = 300_002L)
        assertEquals(TrackingMotionMode.BIKING, engine.snapshot().mode)

        // Third consecutive: streak=3, demotes.
        val out = engine.onAcceptedFix(speedMps = 0.0f, eventTimeMs = 300_003L)
        assertEquals(TrackingMotionMode.WALKING, out.state.mode)
        assertTrue(out.modeChanged)
    }

    @Test
    fun demotion_singleLowSample_doesNotDemoteDrivingToBiking() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        engine.onAcceptedFix(speedMps = 22f, eventTimeMs = 1_000L)
        engine.onAcceptedFix(speedMps = 22f, eventTimeMs = 2_000L)
        assertEquals(TrackingMotionMode.DRIVING, engine.snapshot().mode)
        engine.onTick(nowMs = 300_000L)

        val out = engine.onAcceptedFix(speedMps = 2.0f, eventTimeMs = 300_001L)
        assertEquals(TrackingMotionMode.DRIVING, out.state.mode)
        assertFalse(out.modeChanged)
        assertEquals(1, out.state.consecutiveBelowLower)
    }

    @Test
    fun demotion_requiresThreeConsecutiveSamples_drivingToBiking() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        engine.onAcceptedFix(speedMps = 22f, eventTimeMs = 1_000L)
        engine.onAcceptedFix(speedMps = 22f, eventTimeMs = 2_000L)
        assertEquals(TrackingMotionMode.DRIVING, engine.snapshot().mode)
        engine.onTick(nowMs = 300_000L)

        // First below-lower: streak=1, still DRIVING.
        engine.onAcceptedFix(speedMps = 2.0f, eventTimeMs = 300_001L)
        assertEquals(TrackingMotionMode.DRIVING, engine.snapshot().mode)

        // Second consecutive: streak=2, still DRIVING (threshold is 3).
        engine.onAcceptedFix(speedMps = 2.0f, eventTimeMs = 300_002L)
        assertEquals(TrackingMotionMode.DRIVING, engine.snapshot().mode)

        // Third consecutive: streak=3, demotes.
        val out = engine.onAcceptedFix(speedMps = 2.0f, eventTimeMs = 300_003L)
        assertEquals(TrackingMotionMode.BIKING, out.state.mode)
        assertTrue(out.modeChanged)
    }

    @Test
    fun demotion_streakInterruptedByNeutralSpeed_resetsCounter() {
        // A sample in the neutral band resets the below-lower streak;
        // the subsequent single low sample must not demote.
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        engine.onAcceptedFix(speedMps = 22f, eventTimeMs = 1_000L)
        engine.onAcceptedFix(speedMps = 22f, eventTimeMs = 2_000L)
        assertEquals(TrackingMotionMode.DRIVING, engine.snapshot().mode)
        engine.onTick(nowMs = 300_000L)

        engine.onAcceptedFix(speedMps = 2.0f, eventTimeMs = 300_001L)   // streak=1
        assertEquals(1, engine.snapshot().consecutiveBelowLower)

        // Neutral speed (between 5.5 and 9.0 for DRIVING) resets streak.
        engine.onAcceptedFix(speedMps = 7.0f, eventTimeMs = 300_002L)
        assertEquals(0, engine.snapshot().consecutiveBelowLower)
        assertEquals(TrackingMotionMode.DRIVING, engine.snapshot().mode)

        val out = engine.onAcceptedFix(speedMps = 2.0f, eventTimeMs = 300_003L)  // streak=1 again
        assertEquals(TrackingMotionMode.DRIVING, out.state.mode)
    }

    @Test
    fun demotion_streakInterruptedByRejectedFix_resetsCounter() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        engine.onAcceptedFix(speedMps = 22f, eventTimeMs = 1_000L)
        engine.onAcceptedFix(speedMps = 22f, eventTimeMs = 2_000L)
        assertEquals(TrackingMotionMode.DRIVING, engine.snapshot().mode)
        engine.onTick(nowMs = 300_000L)

        engine.onAcceptedFix(speedMps = 2.0f, eventTimeMs = 300_001L)  // streak=1
        engine.onRejectedFix(eventTimeMs = 300_002L)                    // clears streak
        assertEquals(0, engine.snapshot().consecutiveBelowLower)

        val out = engine.onAcceptedFix(speedMps = 2.0f, eventTimeMs = 300_003L)  // streak=1
        assertEquals(TrackingMotionMode.DRIVING, out.state.mode)
    }

    @Test
    fun onGpsPaused_doesNotDecaySmoothedSpeed_unlikeAcceptedFixAtZero() {
        // onGpsPaused() must preserve smoothedSpeedMps so that the
        // consecutive-demotion streak is not advanced by a zero-speed
        // stationary pause. MotionSubsystem relies on this: it skips
        // onAcceptedFix when GPS is being paused and calls onGpsPaused
        // (via pauseGps) instead.
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        engine.onAcceptedFix(speedMps = 22f, eventTimeMs = 1_000L)
        engine.onAcceptedFix(speedMps = 22f, eventTimeMs = 2_000L)
        assertEquals(TrackingMotionMode.DRIVING, engine.snapshot().mode)
        val smoothedBeforePause = engine.snapshot().smoothedSpeedMps

        engine.onGpsPaused(nowMs = 3_000L)

        val smoothedAfterPause = engine.snapshot().smoothedSpeedMps
        assertTrue(
            "onGpsPaused must not decay smoothedSpeedMps (was $smoothedBeforePause, got $smoothedAfterPause)",
            smoothedAfterPause == smoothedBeforePause,
        )
        assertEquals(0, engine.snapshot().consecutiveBelowLower)
        assertEquals(TrackingMotionMode.DRIVING, engine.snapshot().mode)
    }

    /**
     * Regression proof for the GPS-pause oscillation bug.
     *
     * Before the fix, [com.geovault.tracker.positioning.motion.MotionSubsystem] called
     * [onAcceptedFix] with speed 0 unconditionally even when
     * [com.geovault.tracker.collection.GpsCollectionController.pauseGps] had already
     * called [onGpsPaused] for the same event.
     *
     * Each [onAcceptedFix] call updates the EMA smoother: `nextSmoothed = 0.7 * prev`.
     * After enough GPS-pause cycles the smoother crosses below
     * `DRIVING_TO_BIKING_LOWER_MPS` (5.5 m/s), and [DEMOTE_CONSECUTIVE_REQUIRED] (3)
     * consecutive below-threshold readings then demote DRIVING → BIKING — even while
     * the vehicle is momentarily stationary and will shortly resume driving speed.
     *
     * The fix adds a guard in MotionSubsystem: when GPS is being paused, [onAcceptedFix]
     * is skipped. This test confirms both paths side by side: bug path demotes DRIVING;
     * fix path keeps it.
     */
    @Test
    fun acceptedFixAtZero_afterGpsPaused_decaysSmootherAndDemotesDriving() {
        fun engineInDriving(): Pair<AutoTrackingMotionEngine, Long> {
            val engine = AutoTrackingMotionEngine()
            engine.reset(nowMs = 0L)
            var ts = 1_000L
            repeat(10) { engine.onAcceptedFix(speedMps = 22f, eventTimeMs = ts); ts += 1_000L }
            assertEquals(TrackingMotionMode.DRIVING, engine.snapshot().mode)
            return engine to ts
        }

        // Bug path: every GPS-pause cycle also feeds onAcceptedFix(0), which decays
        // the smoother (0.7^n factor) until it drops below the lower threshold and
        // three consecutive below-threshold decisions demote DRIVING → BIKING.
        val (bugEngine, bugTs0) = engineInDriving()
        var bugTs = bugTs0
        repeat(8) {
            bugEngine.onGpsPaused(nowMs = bugTs)
            bugEngine.onAcceptedFix(speedMps = 0f, eventTimeMs = bugTs)
            bugTs += 20_000L
        }
        assertEquals(
            "bug path: repeated zero-speed accepted fixes during GPS-pause cycles demote DRIVING",
            TrackingMotionMode.BIKING,
            bugEngine.snapshot().mode,
        )

        // Fix path: only onGpsPaused is fed; the smoother is untouched.
        val (fixEngine, fixTs0) = engineInDriving()
        var fixTs = fixTs0
        repeat(8) {
            fixEngine.onGpsPaused(nowMs = fixTs)
            fixTs += 20_000L
        }
        assertEquals(
            "fix path: onGpsPaused alone does not decay the smoother or demote DRIVING",
            TrackingMotionMode.DRIVING,
            fixEngine.snapshot().mode,
        )
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
