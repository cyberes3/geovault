package com.geovault.tracker.location

import com.geovault.tracker.policy.TrackPointDecisionMetrics
import com.geovault.tracker.policy.filter.FilterReason
import com.geovault.tracker.services.TrackingMotionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTrackingMotionCoordinatorTest {

    @Test
    fun neutralHold_doesNotClearPendingMotionPromotion() {
        val engine = AutoTrackingMotionEngine()
        val coordinator = AutoTrackingMotionCoordinator(
            engine = engine,
            evidenceGate = AutoTrackingMotionEvidenceGate(),
            streakPreserveWindowMs = 10_000L,
        )
        engine.reset(nowMs = 0L)

        val first = coordinator.onRejectedOrHeld(
            metrics = metrics(
                lat = 12.0,
                lon = -45.0,
                speedMps = 12.0,
                reason = FilterReason.SPEED_CAP_EXCEEDED.wireValue,
            ),
            rejectReason = null,
            eventTimeMs = 1_000L,
            nowMs = 1_000L,
        )
        val hold = coordinator.onRejectedOrHeld(
            metrics = metrics(
                lat = 12.0005,
                lon = -44.9975,
                speedMps = 9.0,
                reason = FilterReason.STALE_RELOCATION_UNCONFIRMED.wireValue,
            ),
            rejectReason = null,
            eventTimeMs = 11_000L,
            nowMs = 11_000L,
        )
        val second = coordinator.onRejectedOrHeld(
            metrics = metrics(
                lat = 12.0,
                lon = -44.997,
                speedMps = 12.0,
                reason = FilterReason.SPEED_CAP_EXCEEDED.wireValue,
            ),
            rejectReason = null,
            eventTimeMs = 21_000L,
            nowMs = 21_000L,
        )

        assertTrue(first is AutoMotionRejectHandling.Evidence)
        assertTrue(hold is AutoMotionRejectHandling.Preserved)
        assertTrue(second is AutoMotionRejectHandling.Evidence)
        assertEquals(TrackingMotionMode.DRIVING, (second as AutoMotionRejectHandling.Evidence).output.state.mode)
    }

    /**
     * When a speed-cap-exceeded fix does not trigger FAST_EMIT (accuracy above the
     * fast-emit threshold), the gate stores the observation and returns null. Previously
     * the coordinator fell through to evidenceGate.reset() + engine.onRejectedFix(),
     * destroying both the accumulated observation and the engine promotion streak.
     * The fix is to return Preserved instead, leaving the gate state intact.
     */
    @Test
    fun evidenceReasonMiss_returnsPreservedNotRejected() {
        val engine = AutoTrackingMotionEngine()
        val coordinator = AutoTrackingMotionCoordinator(
            engine = engine,
            evidenceGate = AutoTrackingMotionEvidenceGate(),
            streakPreserveWindowMs = 60_000L,
        )
        engine.reset(nowMs = 0L)

        // accuracy=18m exceeds fastEmitAccuracyMeters=10m → gate stores seed, returns null
        val result = coordinator.onRejectedOrHeld(
            metrics = metrics(
                lat = 39.0,
                lon = -104.0,
                speedMps = 33.0,
                reason = FilterReason.SPEED_CAP_EXCEEDED.wireValue,
                accuracyMeters = 18f,
            ),
            rejectReason = null,
            eventTimeMs = 0L,
            nowMs = 0L,
        )

        assertTrue(result is AutoMotionRejectHandling.Preserved)
    }

    /**
     * Full highway-drive scenario: two speed-cap-exceeded fixes interleaved with
     * stale-relocation-unconfirmed holds, all at accuracy above the fast-emit threshold.
     *
     * The observation gap between consecutive cap-evidence fixes is 40 s (double the
     * 20 s GPS polling interval), which is the pattern that exposed both bugs:
     * - Bug 1: coordinator was destroying gate state on the first cap-evidence miss
     * - Bug 2: gate was using metrics.elapsedSeconds (20 s) instead of the
     *   actual observation gap (40 s) for the continuity allowance
     *
     * With both fixes the HANDSHAKE fires on cap2, and the second HANDSHAKE on cap3
     * satisfies the 2-streak requirement for WALKING → DRIVING via SKIP_TO_DRIVING.
     */
    @Test
    fun sparseCapEvidence_withInterleavedHold_promotesToDriving() {
        val engine = AutoTrackingMotionEngine()
        val coordinator = AutoTrackingMotionCoordinator(
            engine = engine,
            evidenceGate = AutoTrackingMotionEvidenceGate(),
            streakPreserveWindowMs = 120_000L,
        )
        engine.reset(nowMs = 0L)

        // cap1 — seed stored, no FAST_EMIT (accuracy > 10 m)
        val cap1 = coordinator.onRejectedOrHeld(
            metrics = metrics(lat = 39.0000, lon = -104.0000, speedMps = 33.0, reason = FilterReason.SPEED_CAP_EXCEEDED.wireValue, accuracyMeters = 18f),
            rejectReason = null, eventTimeMs = 0L, nowMs = 0L,
        )
        // hold1 — stale-relocation interleaves; gate must not be disturbed
        val hold1 = coordinator.onRejectedOrHeld(
            metrics = metrics(lat = 39.0060, lon = -104.0000, speedMps = 33.0, reason = FilterReason.STALE_RELOCATION_UNCONFIRMED.wireValue, accuracyMeters = 18f),
            rejectReason = null, eventTimeMs = 20_000L, nowMs = 20_000L,
        )
        // cap2 — 40 s after cap1; HANDSHAKE fires → Evidence, engine streak 0→1
        val cap2 = coordinator.onRejectedOrHeld(
            metrics = metrics(lat = 39.0119, lon = -104.0000, speedMps = 33.0, reason = FilterReason.SPEED_CAP_EXCEEDED.wireValue, accuracyMeters = 18f),
            rejectReason = null, eventTimeMs = 40_000L, nowMs = 40_000L,
        )
        // hold2 — another stale-relocation; streak must be preserved
        val hold2 = coordinator.onRejectedOrHeld(
            metrics = metrics(lat = 39.0179, lon = -104.0000, speedMps = 33.0, reason = FilterReason.STALE_RELOCATION_UNCONFIRMED.wireValue, accuracyMeters = 18f),
            rejectReason = null, eventTimeMs = 60_000L, nowMs = 60_000L,
        )
        // cap3 — 40 s after cap2; HANDSHAKE fires → Evidence, engine streak 1→2 → DRIVING
        val cap3 = coordinator.onRejectedOrHeld(
            metrics = metrics(lat = 39.0238, lon = -104.0000, speedMps = 33.0, reason = FilterReason.SPEED_CAP_EXCEEDED.wireValue, accuracyMeters = 18f),
            rejectReason = null, eventTimeMs = 80_000L, nowMs = 80_000L,
        )

        assertTrue(cap1 is AutoMotionRejectHandling.Preserved)
        assertTrue(hold1 is AutoMotionRejectHandling.Preserved)
        assertTrue(cap2 is AutoMotionRejectHandling.Evidence)
        assertTrue(hold2 is AutoMotionRejectHandling.Preserved)
        assertTrue(cap3 is AutoMotionRejectHandling.Evidence)
        assertEquals(TrackingMotionMode.DRIVING, (cap3 as AutoMotionRejectHandling.Evidence).output.state.mode)
    }

    private fun metrics(
        lat: Double,
        lon: Double,
        speedMps: Double,
        reason: String,
        accuracyMeters: Float = 8f,
    ): TrackPointDecisionMetrics {
        return TrackPointDecisionMetrics(
            rawDistanceMeters = speedMps * 20.0,
            effectiveDistanceMeters = speedMps * 20.0,
            elapsedSeconds = 20.0,
            impliedSpeedMps = speedMps,
            accuracyMeters = accuracyMeters,
            rollingAverageStepMeters = 0.0,
            capCandidateMeters = 100.0,
            decision = "rejected",
            reason = reason,
            rawLatitude = lat,
            rawLongitude = lon,
        )
    }
}
