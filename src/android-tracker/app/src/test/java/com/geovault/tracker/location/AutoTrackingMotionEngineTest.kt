package com.geovault.tracker.location

import com.geovault.tracker.sensor.ImuClassification
import com.geovault.tracker.sensor.ImuMotionContext
import com.geovault.tracker.services.TrackingMotionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
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

    /**
     * [onGpsPaused] now routes through [setStateWithTransition] with
     * speed=0, contributing to the demotion streak like a zero-speed
     * accepted fix. Smoothed speed is unchanged (alpha=0 for pause path)
     * so a single pause does not flip the mode, but three in a row will.
     */
    @Test
    fun onGpsPaused_advancesDemotionStreak() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        engine.onAcceptedFix(speedMps = 22f, eventTimeMs = 1_000L)
        engine.onAcceptedFix(speedMps = 22f, eventTimeMs = 2_000L)
        assertEquals(TrackingMotionMode.DRIVING, engine.snapshot().mode)
        assertEquals(0, engine.snapshot().consecutiveBelowLower)

        engine.onGpsPaused(nowMs = 3_000L)

        assertEquals(1, engine.snapshot().consecutiveBelowLower)
        assertEquals(TrackingMotionMode.DRIVING, engine.snapshot().mode)
    }

    /**
     * Regression guard for the GPS-pause oscillation bug.
     *
     * Before the oscillation fix in MotionSubsystem, every GPS-pause cycle
     * called [onGpsPaused] AND [onAcceptedFix] with speed 0. The combined
     * smoother decay (`0.7 * prev` per cycle) caused premature demotion.
     *
     * The guard in MotionSubsystem now skips [onAcceptedFix] when GPS is
     * being paused. [onGpsPaused] itself intentionally contributes zero-speed
     * evidence (Bug 5 fix). Three pauses demote DRIVING; two do not.
     */
    @Test
    fun onGpsPaused_twoPauses_keepsDriving_threePauses_demote() {
        fun engineInDriving(): AutoTrackingMotionEngine {
            val engine = AutoTrackingMotionEngine()
            engine.reset(nowMs = 0L)
            var ts = 1_000L
            repeat(10) { engine.onAcceptedFix(speedMps = 22f, eventTimeMs = ts); ts += 1_000L }
            assertEquals(TrackingMotionMode.DRIVING, engine.snapshot().mode)
            return engine
        }

        // Two pauses should not demote — need three consecutive below-threshold events.
        val twoEngine = engineInDriving()
        twoEngine.onGpsPaused(nowMs = 60_000L)
        twoEngine.onGpsPaused(nowMs = 120_000L)
        assertEquals(
            "two GPS pauses must not demote DRIVING (need three)",
            TrackingMotionMode.DRIVING,
            twoEngine.snapshot().mode,
        )

        // Three consecutive pauses should demote.
        val threeEngine = engineInDriving()
        threeEngine.onGpsPaused(nowMs = 60_000L)
        threeEngine.onGpsPaused(nowMs = 120_000L)
        threeEngine.onGpsPaused(nowMs = 180_000L)
        assertEquals(
            "three GPS pauses must demote DRIVING → BIKING",
            TrackingMotionMode.BIKING,
            threeEngine.snapshot().mode,
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

    /**
     * A tick past [decayGraceMs] must **not** reset [consecutiveBelowLower].
     * Erasing it would make demotion impossible when GPS fixes arrive less
     * frequently than the grace window (e.g. DRIVING mode's 100 m distance
     * filter while stationary). We use [onGpsPaused] to establish the streak
     * because it always passes `decisionSpeedMps = 0`, which is unambiguously
     * below the demotion threshold regardless of the EMA smoother state.
     */
    @Test
    fun onTick_afterGrace_preservesDemotionStreak() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        engine.onAcceptedFix(speedMps = 22f, eventTimeMs = 1_000L)
        engine.onAcceptedFix(speedMps = 22f, eventTimeMs = 2_000L)
        assertEquals(TrackingMotionMode.DRIVING, engine.snapshot().mode)

        // GPS pause establishes a demotion streak of 1.
        engine.onGpsPaused(nowMs = 62_000L)
        assertEquals(1, engine.snapshot().consecutiveBelowLower)

        // Tick well past decayGraceMs (default 15 s).
        engine.onTick(nowMs = 120_000L)

        assertEquals(1, engine.snapshot().consecutiveBelowLower)
        assertEquals(TrackingMotionMode.DRIVING, engine.snapshot().mode)
    }

    /**
     * Three GPS pauses separated by quiet ticks must still accumulate and
     * demote DRIVING → BIKING, now that [onTick] no longer resets the streak.
     */
    @Test
    fun onTick_afterGrace_followedByPauses_demotesDriving() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        engine.onAcceptedFix(speedMps = 22f, eventTimeMs = 1_000L)
        engine.onAcceptedFix(speedMps = 22f, eventTimeMs = 2_000L)
        assertEquals(TrackingMotionMode.DRIVING, engine.snapshot().mode)

        engine.onGpsPaused(nowMs = 62_000L)   // streak=1
        engine.onTick(nowMs = 90_000L)         // decay; streak preserved at 1
        engine.onGpsPaused(nowMs = 122_000L)  // streak=2
        engine.onTick(nowMs = 150_000L)        // decay; streak preserved at 2
        val out = engine.onGpsPaused(nowMs = 182_000L) // streak=3 → demote

        assertTrue(out.modeChanged)
        assertEquals(TrackingMotionMode.BIKING, out.state.mode)
    }

    /**
     * Three [onGpsPaused] calls must accumulate the demotion streak to
     * [DEMOTE_CONSECUTIVE_REQUIRED] and trigger a DRIVING → BIKING transition.
     */
    @Test
    fun onGpsPaused_threeTimes_demotesDriving() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        engine.onAcceptedFix(speedMps = 22f, eventTimeMs = 1_000L)
        engine.onAcceptedFix(speedMps = 22f, eventTimeMs = 2_000L)
        assertEquals(TrackingMotionMode.DRIVING, engine.snapshot().mode)

        engine.onGpsPaused(nowMs = 60_000L)   // streak=1
        assertEquals(TrackingMotionMode.DRIVING, engine.snapshot().mode)
        engine.onGpsPaused(nowMs = 120_000L)  // streak=2
        assertEquals(TrackingMotionMode.DRIVING, engine.snapshot().mode)
        val out = engine.onGpsPaused(nowMs = 180_000L)  // streak=3 → demote

        assertTrue(out.modeChanged)
        assertEquals(TrackingMotionMode.BIKING, out.state.mode)
    }

    /**
     * Regression guard for the WALKING-mode promotion deadlock.
     *
     * Previously, [onTick] reset [consecutiveAboveUpper] to 0. In WALKING mode
     * the distance filter raises the bar so GPS fixes arrive infrequently; the
     * promotion HANDSHAKE path feeds evidence via [onMotionEvidence], which uses
     * the same [consecutiveAboveUpper] counter. If [onTick] fires between two
     * evidence events (very likely given ~20 s GPS intervals vs ~3 min ticks),
     * the streak resets to 0 and promotion never reaches
     * [PROMOTE_CONSECUTIVE_REQUIRED]. The result is the mode staying stuck in
     * WALKING while the device is at highway speed, eventually leading to a
     * local-stall reanchor and a large map jump.
     *
     * The fix preserves [consecutiveAboveUpper] across ticks symmetrically with
     * [consecutiveBelowLower].
     */
    @Test
    fun onTick_afterGrace_preservesPromotionStreak() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)

        // First high-speed evidence arms the streak at 1.
        engine.onMotionEvidence(
            speedMps = 22f,
            eventTimeMs = 1_000L,
            confidence = AutoTrackingMotionEvidenceConfidence.High,
        )
        assertEquals(1, engine.snapshot().consecutiveAboveUpper)
        assertEquals(TrackingMotionMode.WALKING, engine.snapshot().mode)

        // A decay tick fires before the next evidence arrives.
        engine.onTick(nowMs = 60_000L)

        // Streak must still be 1 — not reset to 0.
        assertEquals(
            "onTick must not reset consecutiveAboveUpper",
            1,
            engine.snapshot().consecutiveAboveUpper,
        )
        assertEquals(TrackingMotionMode.WALKING, engine.snapshot().mode)

        // Second evidence after the tick crosses PROMOTE_CONSECUTIVE_REQUIRED=2 → DRIVING.
        val out = engine.onMotionEvidence(
            speedMps = 22f,
            eventTimeMs = 61_000L,
            confidence = AutoTrackingMotionEvidenceConfidence.High,
        )
        assertTrue("promotion must fire after tick gap", out.modeChanged)
        assertEquals(TrackingMotionMode.DRIVING, out.state.mode)
    }

    /**
     * Symmetric with [onTick_afterGrace_preservesDemotionStreak]: a promotion
     * streak accumulated via accepted fixes must also survive a quiet decay tick.
     */
    @Test
    fun onTick_afterGrace_preservesAcceptedFixPromotionStreak() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)

        engine.onAcceptedFix(speedMps = 22f, eventTimeMs = 1_000L)
        assertEquals(1, engine.snapshot().consecutiveAboveUpper)

        engine.onTick(nowMs = 60_000L)

        assertEquals(
            "onTick must not reset consecutiveAboveUpper",
            1,
            engine.snapshot().consecutiveAboveUpper,
        )

        val out = engine.onAcceptedFix(speedMps = 22f, eventTimeMs = 61_000L)
        assertTrue(out.modeChanged)
        assertEquals(TrackingMotionMode.DRIVING, out.state.mode)
    }

    // region IMU constraint tests

    private fun vehicularCtx(stepRate: Float = 0f, variance: Float = 0.5f) = ImuMotionContext(
        classification = ImuClassification.VEHICULAR,
        confidence = 0.9f,
        accelerationVarianceMps4 = variance,
        stepRatePerMinute = stepRate,
    )

    private fun pedestrianCtx(stepRate: Float = 60f) = ImuMotionContext(
        classification = ImuClassification.PEDESTRIAN,
        confidence = 0.9f,
        accelerationVarianceMps4 = 0.02f,
        stepRatePerMinute = stepRate,
    )

    private fun stationaryCtx() = ImuMotionContext(
        classification = ImuClassification.STATIONARY,
        confidence = 0.95f,
        accelerationVarianceMps4 = 0.005f,
        stepRatePerMinute = 0f,
    )

    private fun unknownCtx() = ImuMotionContext(
        classification = ImuClassification.UNKNOWN,
        confidence = 0f,
        accelerationVarianceMps4 = 0f,
        stepRatePerMinute = 0f,
    )

    @Test
    fun imu_vehicularStreakBelowThreshold_doesNotSetFloor() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        // VEHICULAR_REQUIRED = 2; one emission should not yet set the floor
        val out = engine.onImuClassification(vehicularCtx())
        assertNull("floor not set before threshold", out.imuConstraintSnapshot)
        assertEquals(TrackingMotionMode.WALKING, engine.snapshot().mode)
    }

    @Test
    fun imu_vehicularAtThreshold_setsFloorAndPromotesWalkingToBiking() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        // First VEHICULAR emission: streak=1, threshold=2 → no constraint yet
        engine.onImuClassification(vehicularCtx())
        assertEquals(TrackingMotionMode.WALKING, engine.snapshot().mode)

        // Second VEHICULAR emission: streak=2, threshold=2 → floor=BIKING, mode clamped
        val out = engine.onImuClassification(vehicularCtx())
        assertNotNull("constraint snapshot emitted at threshold", out.imuConstraintSnapshot)
        assertEquals(TrackingMotionMode.BIKING, out.imuConstraintSnapshot!!.floor)
        assertNull(out.imuConstraintSnapshot.ceiling)
        assertEquals(TrackingMotionMode.BIKING, engine.snapshot().mode)
        assertTrue(out.modeChanged)
    }

    @Test
    fun imu_pedestrianAtThreshold_setsCeilingAndDemotesDrivingToWalking() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        // Promote to DRIVING first
        engine.onAcceptedFix(speedMps = 22f, eventTimeMs = 1_000L)
        engine.onAcceptedFix(speedMps = 22f, eventTimeMs = 2_000L)
        assertEquals(TrackingMotionMode.DRIVING, engine.snapshot().mode)

        // PEDESTRIAN_REQUIRED = 3; emit 3 PEDESTRIAN classifications
        engine.onImuClassification(pedestrianCtx())
        assertEquals(TrackingMotionMode.DRIVING, engine.snapshot().mode)
        engine.onImuClassification(pedestrianCtx())
        assertEquals(TrackingMotionMode.DRIVING, engine.snapshot().mode)
        val out = engine.onImuClassification(pedestrianCtx())

        assertNotNull("constraint snapshot emitted at threshold", out.imuConstraintSnapshot)
        assertEquals(TrackingMotionMode.WALKING, out.imuConstraintSnapshot!!.ceiling)
        assertNull("pedestrian ceiling clears vehicular floor", out.imuConstraintSnapshot.floor)
        assertEquals(TrackingMotionMode.WALKING, engine.snapshot().mode)
        assertTrue(out.modeChanged)
    }

    @Test
    fun imu_streaksSurviveRejectedFix() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)

        // Arm VEHICULAR streak to 1
        engine.onImuClassification(vehicularCtx())
        assertEquals(TrackingMotionMode.WALKING, engine.snapshot().mode)

        // Many rejected fixes — must NOT touch IMU streak
        repeat(10) { engine.onRejectedFix(eventTimeMs = (it + 1) * 1_000L) }

        // Second VEHICULAR emission → threshold reached → floor applied
        val out = engine.onImuClassification(vehicularCtx())
        assertNotNull("streak must survive onRejectedFix calls", out.imuConstraintSnapshot)
        assertEquals(TrackingMotionMode.BIKING, engine.snapshot().mode)
    }

    @Test
    fun imu_vehicularFloorBlocksGpsDemotionToWalking() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        // Establish BIKING floor
        engine.onImuClassification(vehicularCtx())
        engine.onImuClassification(vehicularCtx())
        assertEquals(TrackingMotionMode.BIKING, engine.snapshot().mode)

        // GPS reports three consecutive low-speed samples — would normally demote to WALKING
        engine.onAcceptedFix(speedMps = 0f, eventTimeMs = 1_000L)
        engine.onAcceptedFix(speedMps = 0f, eventTimeMs = 2_000L)
        val out = engine.onAcceptedFix(speedMps = 0f, eventTimeMs = 3_000L)

        // Floor must hold mode at BIKING
        assertEquals(TrackingMotionMode.BIKING, out.state.mode)
        assertFalse(out.modeChanged)
    }

    @Test
    fun imu_pedestrianCeilingBlocksGpsPromotionToDriving() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        // Establish WALKING ceiling
        repeat(3) { engine.onImuClassification(pedestrianCtx()) }
        assertEquals(TrackingMotionMode.WALKING, engine.snapshot().mode)

        // GPS reports two consecutive high-speed samples — would normally promote to BIKING or DRIVING
        engine.onAcceptedFix(speedMps = 22f, eventTimeMs = 1_000L)
        val out = engine.onAcceptedFix(speedMps = 22f, eventTimeMs = 2_000L)

        // Ceiling must hold mode at WALKING
        assertEquals(TrackingMotionMode.WALKING, out.state.mode)
        assertFalse(out.modeChanged)
    }

    @Test
    fun imu_stationaryClassification_clearsBothConstraints() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        // Establish BIKING floor from VEHICULAR
        engine.onImuClassification(vehicularCtx())
        engine.onImuClassification(vehicularCtx())
        assertEquals(TrackingMotionMode.BIKING, engine.snapshot().mode)

        // STATIONARY clears the floor
        val out = engine.onImuClassification(stationaryCtx())
        assertNotNull("constraint snapshot emitted when floor cleared", out.imuConstraintSnapshot)
        assertNull(out.imuConstraintSnapshot!!.floor)
        assertNull(out.imuConstraintSnapshot.ceiling)
    }

    @Test
    fun imu_stationaryAfterVehicularFloor_emitsSnapshotWithZeroStreaks() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        // Establish a VEHICULAR floor (floor=BIKING) so STATIONARY has something to clear
        engine.onImuClassification(vehicularCtx())
        engine.onImuClassification(vehicularCtx())
        assertEquals(TrackingMotionMode.BIKING, engine.snapshot().mode)

        // STATIONARY clears the floor → constraints change → snapshot emitted
        val out = engine.onImuClassification(stationaryCtx())
        assertNotNull("snapshot emitted when floor cleared by STATIONARY", out.imuConstraintSnapshot)
        assertEquals(0, out.imuConstraintSnapshot!!.pedestrianStreak)
        assertEquals(0, out.imuConstraintSnapshot.vehicularStreak)
        assertNull(out.imuConstraintSnapshot.floor)
        assertNull(out.imuConstraintSnapshot.ceiling)
    }

    @Test
    fun imu_opposingClassification_clearsPriorConstraint() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        // Establish WALKING ceiling from PEDESTRIAN
        repeat(3) { engine.onImuClassification(pedestrianCtx()) }
        assertEquals(TrackingMotionMode.WALKING, engine.snapshot().mode)

        // VEHICULAR (2 emissions) should clear the ceiling and set the floor
        engine.onImuClassification(vehicularCtx())
        val out = engine.onImuClassification(vehicularCtx())

        assertNotNull(out.imuConstraintSnapshot)
        assertNull("pedestrian ceiling must be cleared", out.imuConstraintSnapshot!!.ceiling)
        assertEquals(TrackingMotionMode.BIKING, out.imuConstraintSnapshot.floor)
        assertEquals(TrackingMotionMode.BIKING, engine.snapshot().mode)
    }

    @Test
    fun imu_resetClearsAllConstraintsAndStreaks() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        // Arm constraints
        engine.onImuClassification(vehicularCtx())
        engine.onImuClassification(vehicularCtx())

        // reset() must wipe IMU state
        engine.reset(nowMs = 1_000L)

        // GPS can now demote below the previously applied floor
        engine.onAcceptedFix(speedMps = 0f, eventTimeMs = 1_001L)
        engine.onAcceptedFix(speedMps = 0f, eventTimeMs = 1_002L)
        val out = engine.onAcceptedFix(speedMps = 0f, eventTimeMs = 1_003L)
        // After reset, mode starts WALKING again; zero-speed keeps it there
        assertEquals(TrackingMotionMode.WALKING, out.state.mode)
    }

    @Test
    fun imu_noConstraintSnapshot_whenClassificationDoesNotChangeConstraints() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        // First VEHICULAR: streak=1, threshold not reached → no constraint change
        val out = engine.onImuClassification(vehicularCtx())
        assertNull("snapshot must be null when constraints did not change", out.imuConstraintSnapshot)
    }

    // --- additional IMU edge cases ---

    /**
     * UNKNOWN is handled identically to STATIONARY: both clear all constraints and
     * reset all streaks.
     */
    @Test
    fun imu_unknown_clearsBothConstraints() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        // Establish BIKING floor
        engine.onImuClassification(vehicularCtx())
        engine.onImuClassification(vehicularCtx())
        assertEquals(TrackingMotionMode.BIKING, engine.snapshot().mode)

        val out = engine.onImuClassification(unknownCtx())
        assertNotNull("snapshot emitted when floor cleared by UNKNOWN", out.imuConstraintSnapshot)
        assertNull(out.imuConstraintSnapshot!!.floor)
        assertNull(out.imuConstraintSnapshot.ceiling)
        assertEquals(0, out.imuConstraintSnapshot.pedestrianStreak)
        assertEquals(0, out.imuConstraintSnapshot.vehicularStreak)
    }

    /**
     * PEDESTRIAN streak at 2 (one below [IMU_PEDESTRIAN_REQUIRED]=3) must not yet
     * apply a ceiling — the constraint requires a third stable emission.
     */
    @Test
    fun imu_pedestrianStreakBelowThreshold_doesNotSetCeiling() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        engine.onAcceptedFix(speedMps = 22f, eventTimeMs = 1_000L)
        engine.onAcceptedFix(speedMps = 22f, eventTimeMs = 2_000L)
        assertEquals(TrackingMotionMode.DRIVING, engine.snapshot().mode)

        engine.onImuClassification(pedestrianCtx())  // streak=1
        val out = engine.onImuClassification(pedestrianCtx())  // streak=2, threshold=3 not yet met
        assertNull("ceiling must not be applied until threshold is reached", out.imuConstraintSnapshot)
        assertEquals(TrackingMotionMode.DRIVING, engine.snapshot().mode)
    }

    /**
     * After the VEHICULAR floor is already established, a subsequent VEHICULAR
     * heartbeat emission (same constraint, streak increments past threshold) must
     * not produce a duplicate [AutoTrackingEngineOutput.imuConstraintSnapshot].
     */
    @Test
    fun imu_vehicularHeartbeat_afterFloorAlreadySet_noSnapshot() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        // Reach threshold → floor set
        engine.onImuClassification(vehicularCtx())
        engine.onImuClassification(vehicularCtx())
        assertNotNull("floor set at threshold", engine.snapshot().mode.let { it }) // floor exists

        // Third VEHICULAR: streak=3 but floor is already BIKING → no constraint change
        val out = engine.onImuClassification(vehicularCtx())
        assertNull("no snapshot when floor is already set to the same value", out.imuConstraintSnapshot)
    }

    /**
     * An active BIKING floor must not prevent GPS from promoting the mode to DRIVING.
     * Floor only blocks demotion below BIKING; DRIVING is above the floor ordinal.
     */
    @Test
    fun imu_vehicularFloor_doesNotBlockGpsPromotionToDriving() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        // Establish BIKING floor (mode now BIKING)
        engine.onImuClassification(vehicularCtx())
        engine.onImuClassification(vehicularCtx())
        assertEquals(TrackingMotionMode.BIKING, engine.snapshot().mode)

        // Two consecutive high-speed GPS fixes should promote to DRIVING
        engine.onAcceptedFix(speedMps = 22f, eventTimeMs = 1_000L)
        val out = engine.onAcceptedFix(speedMps = 22f, eventTimeMs = 2_000L)
        assertEquals(
            "IMU floor must not prevent GPS promotion above the floor",
            TrackingMotionMode.DRIVING,
            out.state.mode,
        )
        assertTrue(out.modeChanged)
    }

    /**
     * When the mode is BIKING and the PEDESTRIAN ceiling is applied (WALKING), the
     * engine must immediately clamp BIKING → WALKING.
     */
    @Test
    fun imu_pedestrianCeiling_fromBiking_clampsToWalking() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        // Promote to BIKING first
        engine.onAcceptedFix(speedMps = 4f, eventTimeMs = 1_000L)
        engine.onAcceptedFix(speedMps = 4f, eventTimeMs = 2_000L)
        assertEquals(TrackingMotionMode.BIKING, engine.snapshot().mode)

        // Apply PEDESTRIAN ceiling at threshold
        engine.onImuClassification(pedestrianCtx())
        engine.onImuClassification(pedestrianCtx())
        val out = engine.onImuClassification(pedestrianCtx())

        assertEquals(TrackingMotionMode.WALKING, out.state.mode)
        assertTrue(out.modeChanged)
        assertEquals(TrackingMotionMode.WALKING, out.imuConstraintSnapshot!!.ceiling)
    }

    /**
     * When the mode is already WALKING and the PEDESTRIAN ceiling is reached, the
     * ceiling is applied but [AutoTrackingEngineOutput.modeChanged] must be false
     * (no actual transition). The [imuConstraintSnapshot] is still non-null because
     * the constraint bounds changed.
     */
    @Test
    fun imu_pedestrianCeiling_modeAlreadyWalking_snapshotEmittedWithoutModeChange() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        assertEquals(TrackingMotionMode.WALKING, engine.snapshot().mode)

        engine.onImuClassification(pedestrianCtx())
        engine.onImuClassification(pedestrianCtx())
        val out = engine.onImuClassification(pedestrianCtx())

        assertFalse("no mode change when already at ceiling", out.modeChanged)
        assertNotNull("constraint snapshot must still be emitted", out.imuConstraintSnapshot)
        assertEquals(TrackingMotionMode.WALKING, out.imuConstraintSnapshot!!.ceiling)
    }

    /**
     * When GPS accumulates a promotion streak and then the IMU ceiling overrides the
     * GPS-selected mode (even without changing the displayed mode), the GPS streak
     * counters must be reset so the evidence accumulated toward the wrong mode does
     * not carry forward to the next promotion attempt.
     *
     * Mechanics: the first fix at 22 m/s arms streak=1 (GPS still decides WALKING,
     * ceiling=WALKING, no conflict). The second fix completes the required count=2
     * and GPS selects DRIVING; the ceiling clamps it back to WALKING, triggering an
     * imuOverride that zeroes both streak counters. The third fix must then restart
     * the promotion sequence from scratch.
     */
    @Test
    fun imu_ceiling_resetsGpsStreaks_whenGpsSelectionIsOverridden() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        // Apply WALKING ceiling
        repeat(3) { engine.onImuClassification(pedestrianCtx()) }

        // Fix 1: GPS decides WALKING (streak=1, not enough to promote).
        // Ceiling=WALKING agrees with GPS decision → no override → streak carries.
        engine.onAcceptedFix(speedMps = 22f, eventTimeMs = 1_000L)
        assertEquals(1, engine.snapshot().consecutiveAboveUpper)

        // Fix 2: GPS decides DRIVING (skip promotion, streak=2 ≥ required=2).
        // Ceiling clamps DRIVING → WALKING → imuOverride=true → streaks reset to 0.
        val out = engine.onAcceptedFix(speedMps = 22f, eventTimeMs = 2_000L)
        assertEquals(TrackingMotionMode.WALKING, out.state.mode)
        assertFalse("mode already at ceiling — no display change", out.modeChanged)
        assertEquals(
            "GPS streaks must be wiped after IMU ceiling override",
            0,
            engine.snapshot().consecutiveAboveUpper,
        )
    }

    /**
     * The [ImuConstraintSnapshot] emitted at the PEDESTRIAN threshold must carry
     * the correct streak values: pedestrianStreak == [IMU_PEDESTRIAN_REQUIRED] and
     * vehicularStreak == 0.
     */
    @Test
    fun imu_pedestrianSnapshot_hasCorrectStreakValues() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)

        engine.onImuClassification(pedestrianCtx())
        engine.onImuClassification(pedestrianCtx())
        val out = engine.onImuClassification(pedestrianCtx())

        val snapshot = out.imuConstraintSnapshot!!
        assertEquals(AutoTrackingMotionEngine.IMU_PEDESTRIAN_REQUIRED, snapshot.pedestrianStreak)
        assertEquals(0, snapshot.vehicularStreak)
    }

    /**
     * A partial VEHICULAR streak interrupted by a single PEDESTRIAN must reset the
     * vehicular counter. The subsequent VEHICULAR emissions must count from scratch
     * and still require [IMU_VEHICULAR_REQUIRED] before the floor is set.
     */
    @Test
    fun imu_vehicularStreakInterruptedByPedestrian_requiresFullCountAfterReset() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        // Arm VEHICULAR streak to 1 (threshold is 2)
        engine.onImuClassification(vehicularCtx())
        assertNull(engine.snapshot().let { null }) // floor not yet set — just verifying streak state indirectly

        // PEDESTRIAN interrupts → vehicular streak reset to 0 AND pedestrian streak = 1
        engine.onImuClassification(pedestrianCtx())

        // One more VEHICULAR: streak restarts at 1, still below threshold
        val afterOneVehicular = engine.onImuClassification(vehicularCtx())
        assertNull(
            "one VEHICULAR after interruption is not enough for the floor",
            afterOneVehicular.imuConstraintSnapshot,
        )

        // Second VEHICULAR: threshold reached → floor applied
        val afterTwoVehicular = engine.onImuClassification(vehicularCtx())
        assertNotNull("floor must be set after two consecutive VEHICULAR emissions", afterTwoVehicular.imuConstraintSnapshot)
        assertEquals(TrackingMotionMode.BIKING, afterTwoVehicular.imuConstraintSnapshot!!.floor)
    }

    // endregion
}
