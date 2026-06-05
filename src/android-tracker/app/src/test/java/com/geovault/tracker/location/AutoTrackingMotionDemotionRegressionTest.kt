package com.geovault.tracker.location

import com.geovault.tracker.services.TrackingMotionMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for the symmetric demotion streak guard introduced to fix
 * the 2026-06-05 drive spiral.
 *
 * Root cause: a single accepted fix with a low effective speed immediately
 * demoted DRIVING → BIKING → WALKING. The wider 20 s / 7 m WALKING GPS
 * interval then caused subsequent fixes to arrive stale, appearing as large
 * spatial jumps. The jump rejection kept the mode pinned in WALKING, which in
 * turn kept the wide interval — an unrecoverable spiral for the remainder of
 * the drive.
 *
 * Fix: [DEMOTE_CONSECUTIVE_REQUIRED] = 2 consecutive below-lower accepted
 * fixes are required before any downward mode transition, matching the
 * existing [PROMOTE_CONSECUTIVE_REQUIRED] = 2 guard on promotion. The fix is
 * applied symmetrically to both DRIVING → BIKING and BIKING → WALKING.
 *
 * Coordinates and timestamps are entirely synthetic.
 */
class AutoTrackingMotionDemotionRegressionTest {

    // ─── DRIVING spiral regression ────────────────────────────────────────────

    @Test
    fun driving_singleAnomalousLowSpeedFix_doesNotDemote() {
        // Failure mode: GPS dead zone causes EMA to decay; first accepted fix
        // after recovery has near-zero effective speed (uncertainty suppression
        // kept it at the same position). Pre-fix this single sample caused an
        // immediate DRIVING → BIKING transition.
        val engine = buildEngineInDriving()
        // Decay EMA to near-zero so the decision speed is governed by the observed
        // value rather than the residual smoothed highway speed.
        engine.onTick(nowMs = BASE_MS + 300_000L)

        engine.onAcceptedFix(speedMps = 0f, eventTimeMs = BASE_MS + 300_001L)

        assertEquals(
            "Single low-speed fix after EMA decay must not demote from DRIVING",
            TrackingMotionMode.DRIVING,
            engine.snapshot().mode,
        )
        assertEquals(
            "Demotion streak counter must be 1 after one below-lower fix",
            1,
            engine.snapshot().consecutiveBelowLower,
        )
    }

    @Test
    fun driving_lowSpeedFixFollowedByHighSpeed_streakResets_doesNotDemote() {
        // Scenario: GPS artifact gives one slow fix, then the user is clearly
        // still at highway speed. Streak must reset so the drive continues.
        val engine = buildEngineInDriving()
        engine.onTick(nowMs = BASE_MS + 300_000L)

        engine.onAcceptedFix(speedMps = 0f, eventTimeMs = BASE_MS + 300_001L)
        assertEquals(1, engine.snapshot().consecutiveBelowLower)

        // Next accepted fix is highway speed → streak resets.
        engine.onAcceptedFix(speedMps = 20f, eventTimeMs = BASE_MS + 300_002L)

        assertEquals(
            "Mode must stay DRIVING after streak is reset by a high-speed fix",
            TrackingMotionMode.DRIVING,
            engine.snapshot().mode,
        )
        assertEquals(
            "Below-lower streak must be reset to 0 by a high-speed fix",
            0,
            engine.snapshot().consecutiveBelowLower,
        )
    }

    @Test
    fun driving_lowSpeedFixFollowedByRejectedFix_streakResets_doesNotDemote() {
        // A rejected fix (jump, stale relocation, bad accuracy) between two
        // low-speed accepted fixes resets the demotion streak. Two separated
        // low-speed samples separated by a rejection must not demote.
        val engine = buildEngineInDriving()
        engine.onTick(nowMs = BASE_MS + 300_000L)

        engine.onAcceptedFix(speedMps = 0f, eventTimeMs = BASE_MS + 300_001L)
        assertEquals(1, engine.snapshot().consecutiveBelowLower)

        engine.onRejectedFix(eventTimeMs = BASE_MS + 300_002L)
        assertEquals(
            "Rejected fix must clear the below-lower streak",
            0,
            engine.snapshot().consecutiveBelowLower,
        )

        engine.onAcceptedFix(speedMps = 0f, eventTimeMs = BASE_MS + 300_003L)
        assertEquals(
            "Mode must stay DRIVING: streak was reset so only one consecutive slow fix exists",
            TrackingMotionMode.DRIVING,
            engine.snapshot().mode,
        )
    }

    @Test
    fun driving_twoConsecutiveLowSpeedFixes_demotesToBiking() {
        // Legitimate stop (red light, parking): two consecutive fixes both
        // below the lower threshold must still demote correctly.
        val engine = buildEngineInDriving()
        engine.onTick(nowMs = BASE_MS + 300_000L)

        engine.onAcceptedFix(speedMps = 0f, eventTimeMs = BASE_MS + 300_001L)
        assertEquals(TrackingMotionMode.DRIVING, engine.snapshot().mode)

        engine.onAcceptedFix(speedMps = 0f, eventTimeMs = BASE_MS + 300_002L)
        assertEquals(
            "Two consecutive below-lower fixes must demote from DRIVING to BIKING",
            TrackingMotionMode.BIKING,
            engine.snapshot().mode,
        )
    }

    @Test
    fun driving_spiral_singleBadFixDoesNotPinWalkingMode() {
        // End-to-end spiral regression. Before the fix:
        //   1. Single low-speed fix → DRIVING → BIKING → WALKING.
        //   2. WALKING GPS interval (20 s / 7 m) causes staleness → jumps.
        //   3. Jumps are rejected; rejected fixes clear the promotion streak.
        //   4. Mode never recovers to DRIVING for the rest of the drive.
        //
        // After the fix, step 1 is absorbed (streak guard), so the WALKING
        // interval is never activated and highway-speed fixes continue to
        // be processed under the 10 s / 100 m DRIVING profile.
        val engine = buildEngineInDriving()
        engine.onTick(nowMs = BASE_MS + 300_000L)

        // One anomalous GPS artifact: zero effective speed.
        engine.onAcceptedFix(speedMps = 0f, eventTimeMs = BASE_MS + 300_001L)

        // Driver is still on the highway; next fix is at highway speed.
        engine.onAcceptedFix(speedMps = 22f, eventTimeMs = BASE_MS + 300_011L)

        assertEquals(
            "After absorbing one bad fix and resuming highway speed, mode must remain DRIVING",
            TrackingMotionMode.DRIVING,
            engine.snapshot().mode,
        )
    }

    // ─── BIKING spiral regression ─────────────────────────────────────────────

    @Test
    fun biking_singleLowSpeedFix_doesNotDemoteToWalking() {
        val engine = buildEngineInBiking()
        engine.onTick(nowMs = BASE_MS + 300_000L)

        engine.onAcceptedFix(speedMps = 0f, eventTimeMs = BASE_MS + 300_001L)

        assertEquals(
            "Single low-speed fix after EMA decay must not demote from BIKING",
            TrackingMotionMode.BIKING,
            engine.snapshot().mode,
        )
        assertEquals(1, engine.snapshot().consecutiveBelowLower)
    }

    @Test
    fun biking_twoConsecutiveLowSpeedFixes_demotesToWalking() {
        val engine = buildEngineInBiking()
        engine.onTick(nowMs = BASE_MS + 300_000L)

        engine.onAcceptedFix(speedMps = 0f, eventTimeMs = BASE_MS + 300_001L)
        assertEquals(TrackingMotionMode.BIKING, engine.snapshot().mode)

        engine.onAcceptedFix(speedMps = 0f, eventTimeMs = BASE_MS + 300_002L)
        assertEquals(
            "Two consecutive below-lower fixes must demote from BIKING to WALKING",
            TrackingMotionMode.WALKING,
            engine.snapshot().mode,
        )
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private companion object {
        private const val BASE_MS = 0L
    }

    /**
     * Returns an engine freshly promoted to DRIVING via the skip path
     * (two consecutive accepted fixes well above WALKING_SKIP_TO_DRIVING_MPS).
     */
    private fun buildEngineInDriving(): AutoTrackingMotionEngine {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = BASE_MS)
        engine.onAcceptedFix(speedMps = 22f, eventTimeMs = BASE_MS + 10_000L)
        engine.onAcceptedFix(speedMps = 22f, eventTimeMs = BASE_MS + 20_000L)
        check(engine.snapshot().mode == TrackingMotionMode.DRIVING) {
            "buildEngineInDriving: unexpected mode ${engine.snapshot().mode}"
        }
        return engine
    }

    /**
     * Returns an engine freshly promoted to BIKING (two consecutive accepted
     * fixes above WALKING_TO_BIKING_UPPER_MPS but below BIKING_TO_DRIVING_UPPER_MPS).
     */
    private fun buildEngineInBiking(): AutoTrackingMotionEngine {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = BASE_MS)
        engine.onAcceptedFix(speedMps = 6f, eventTimeMs = BASE_MS + 10_000L)
        engine.onAcceptedFix(speedMps = 6f, eventTimeMs = BASE_MS + 20_000L)
        check(engine.snapshot().mode == TrackingMotionMode.BIKING) {
            "buildEngineInBiking: unexpected mode ${engine.snapshot().mode}"
        }
        return engine
    }
}
