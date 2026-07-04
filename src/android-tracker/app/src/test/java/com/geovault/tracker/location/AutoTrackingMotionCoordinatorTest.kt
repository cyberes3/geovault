package com.geovault.tracker.location

import com.geovault.tracker.policy.TrackPointDecisionMetrics
import com.geovault.tracker.policy.TrackPointRejectReason
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
        // accuracy=45m: realistic for a stale-relocation-unconfirmed hold (the real
        // 2026-07-01 incident's such rejects ran ~25-45m) and exceeds
        // maxAccuracyMeters=35m, so this stays a true neutral hold even under
        // AutoTrackingMotionEvidenceGate.evaluateStaleRelocation.
        val hold = coordinator.onRejectedOrHeld(
            metrics = metrics(
                lat = 12.0005,
                lon = -44.9975,
                speedMps = 9.0,
                reason = FilterReason.STALE_RELOCATION_UNCONFIRMED.wireValue,
                accuracyMeters = 45f,
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
        // hold1 — stale-relocation interleaves; gate must not be disturbed. accuracy=40m
        // (realistic for this reject reason, and above maxAccuracyMeters=35m) keeps this
        // a true neutral hold even under evaluateStaleRelocation.
        val hold1 = coordinator.onRejectedOrHeld(
            metrics = metrics(lat = 39.0060, lon = -104.0000, speedMps = 33.0, reason = FilterReason.STALE_RELOCATION_UNCONFIRMED.wireValue, accuracyMeters = 40f),
            rejectReason = null, eventTimeMs = 20_000L, nowMs = 20_000L,
        )
        // cap2 — 40 s after cap1; HANDSHAKE fires → Evidence, engine streak 0→1
        val cap2 = coordinator.onRejectedOrHeld(
            metrics = metrics(lat = 39.0119, lon = -104.0000, speedMps = 33.0, reason = FilterReason.SPEED_CAP_EXCEEDED.wireValue, accuracyMeters = 18f),
            rejectReason = null, eventTimeMs = 40_000L, nowMs = 40_000L,
        )
        // hold2 — another stale-relocation; streak must be preserved. Same realistic
        // degraded accuracy as hold1.
        val hold2 = coordinator.onRejectedOrHeld(
            metrics = metrics(lat = 39.0179, lon = -104.0000, speedMps = 33.0, reason = FilterReason.STALE_RELOCATION_UNCONFIRMED.wireValue, accuracyMeters = 40f),
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

    /**
     * Mirrors the real 2026-07-01 canyon-highway capture: a speed-cap-exceeded fix
     * seeds the evidence gate's HANDSHAKE (accuracy above fastEmitAccuracyMeters, so
     * no immediate FAST_EMIT), then an unrelated low-accuracy chipset blip arrives
     * before the second half of the handshake. Because no evidence has fired yet in
     * this streak (`lastEvidenceWallClockMs == 0`), `isWithinCapEvidenceWindow` cannot
     * protect the seed -- only [AutoTrackingMotionEvidenceGate.hasPendingObservation]
     * can. Without that check, the interleaved BAD_ACCURACY reject wipes the seed on
     * every occurrence, and in GPS conditions where such rejects recur faster than a
     * clean cap-evidence pair arrives, the handshake never completes and the mode
     * stays stuck on WALKING for the entire drive -- exactly what produced the
     * multi-minute JUMP-reject cascade and the resulting large map jump.
     */
    @Test
    fun transientBadAccuracy_beforeFirstHandshake_doesNotDiscardPendingCapEvidenceSeed() {
        val engine = AutoTrackingMotionEngine()
        val coordinator = AutoTrackingMotionCoordinator(
            engine = engine,
            evidenceGate = AutoTrackingMotionEvidenceGate(),
            streakPreserveWindowMs = 120_000L,
        )
        engine.reset(nowMs = 0L)

        // cap1 -- seed stored, no FAST_EMIT (accuracy=18m > fastEmitAccuracyMeters=10m)
        val cap1 = coordinator.onRejectedOrHeld(
            metrics = metrics(lat = 39.0000, lon = -104.0000, speedMps = 33.0, reason = FilterReason.SPEED_CAP_EXCEEDED.wireValue, accuracyMeters = 18f),
            rejectReason = null, eventTimeMs = 0L, nowMs = 0L,
        )
        // A stray low-accuracy fix interleaves before the handshake partner arrives.
        // No evidence has fired yet in this streak, so only hasPendingObservation can
        // save the seed.
        val badAccuracy = coordinator.onRejectedOrHeld(
            metrics = metrics(lat = 39.0050, lon = -104.0000, speedMps = 0.0, reason = FilterReason.LOW_ACCURACY.wireValue, accuracyMeters = 150f),
            rejectReason = TrackPointRejectReason.BAD_ACCURACY, eventTimeMs = 20_000L, nowMs = 20_000L,
        )
        // cap2 -- 40 s after cap1; HANDSHAKE fires only if the seed survived the
        // interleaved reject above.
        val cap2 = coordinator.onRejectedOrHeld(
            metrics = metrics(lat = 39.0119, lon = -104.0000, speedMps = 33.0, reason = FilterReason.SPEED_CAP_EXCEEDED.wireValue, accuracyMeters = 18f),
            rejectReason = null, eventTimeMs = 40_000L, nowMs = 40_000L,
        )
        // cap3 -- 40 s after cap2; second HANDSHAKE satisfies the 2-streak promotion.
        val cap3 = coordinator.onRejectedOrHeld(
            metrics = metrics(lat = 39.0238, lon = -104.0000, speedMps = 33.0, reason = FilterReason.SPEED_CAP_EXCEEDED.wireValue, accuracyMeters = 18f),
            rejectReason = null, eventTimeMs = 80_000L, nowMs = 80_000L,
        )

        assertTrue(cap1 is AutoMotionRejectHandling.Preserved)
        assertTrue(
            "a transient low-accuracy reject before the first handshake must preserve the pending seed",
            badAccuracy is AutoMotionRejectHandling.Preserved,
        )
        assertTrue(
            "expected the handshake to fire once the seed survives the interleaved reject; got $cap2",
            cap2 is AutoMotionRejectHandling.Evidence,
        )
        assertTrue(cap3 is AutoMotionRejectHandling.Evidence)
        assertEquals(TrackingMotionMode.DRIVING, (cap3 as AutoMotionRejectHandling.Evidence).output.state.mode)
    }

    /**
     * Mirrors the real 2026-07-01 canyon-driving incident: once RelocationRecoveryGate/
     * SpatialConfirmationGate take over a stale-anchor relocation, LocationMetricsEngine's
     * RSS-accuracy suppression (`effectiveDistance = max(0, rawDistance - rssAccuracy)`)
     * zeroes out effectiveDistanceMeters/impliedSpeedMps even during genuine sustained
     * highway motion once accuracy degrades -- the real trace showed `raw=490m effective=0
     * speed=0` on a candidate-unconfirmed/stale-relocation-unconfirmed fix that was
     * actually moving at ~30 m/s. Before `evaluateStaleRelocation` existed, such fixes
     * were treated as neutral holds: no evidence was ever produced from them, so once the
     * filter's reject reason permanently shifted away from speed-cap-exceeded, the
     * evidence gate was starved and mode stayed on WALKING for the rest of the drive (two
     * `local_stall_reanchor` safety-valve firings did not clear it either). A single
     * speed-cap-exceeded HANDSHAKE only advances the promotion streak by one and keeps
     * mode at WALKING (see `sparseCapEvidence_withInterleavedHold_promotesToDriving`), so
     * this test seeds the same first HANDSHAKE via the ordinary evidence path, then
     * completes the *second* HANDSHAKE via a candidate-unconfirmed fix carrying zeroed
     * effective distance/implied speed but a real, continuous rawDistanceMeters -- proving
     * promotion now completes via that field instead of stalling forever.
     */
    @Test
    fun staleRelocationHold_withZeroedEffectiveDistance_completesSecondHandshakeViaRawDistance() {
        val engine = AutoTrackingMotionEngine()
        val coordinator = AutoTrackingMotionCoordinator(
            engine = engine,
            evidenceGate = AutoTrackingMotionEvidenceGate(),
            streakPreserveWindowMs = 120_000L,
        )
        engine.reset(nowMs = 0L)

        // cap1 -- seed stored, no FAST_EMIT (accuracy=18m > fastEmitAccuracyMeters=10m)
        val cap1 = coordinator.onRejectedOrHeld(
            metrics = metrics(lat = 39.0000, lon = -104.0000, speedMps = 33.0, reason = FilterReason.SPEED_CAP_EXCEEDED.wireValue, accuracyMeters = 18f),
            rejectReason = null, eventTimeMs = 0L, nowMs = 0L,
        )
        // cap2 -- 40 s after cap1; ordinary HANDSHAKE fires -> Evidence, engine streak 0->1,
        // mode stays WALKING (a single handshake is not enough for SKIP_TO_DRIVING).
        val cap2 = coordinator.onRejectedOrHeld(
            metrics = metrics(lat = 39.0119, lon = -104.0000, speedMps = 33.0, reason = FilterReason.SPEED_CAP_EXCEEDED.wireValue, accuracyMeters = 18f),
            rejectReason = null, eventTimeMs = 40_000L, nowMs = 40_000L,
        )
        // relocation1 -- 15 s after cap2 (matching the real incident's relocation-hold
        // cadence), continuing the same course/speed. effectiveDistanceMeters and
        // impliedSpeedMps are zeroed exactly as LocationMetricsEngine's RSS-accuracy
        // suppression would produce; only rawDistanceMeters/elapsedSeconds carries the
        // real ~33 m/s. The ordinary evaluate() path would treat this fix as unusable
        // (isSupportedReason rejects the reason entirely); evaluateStaleRelocation must
        // complete the handshake instead.
        val relocation1 = coordinator.onRejectedOrHeld(
            metrics = staleRelocationMetrics(
                lat = 39.0163625,
                lon = -104.0000,
                rawSpeedMps = 33.0,
                elapsedSeconds = 15.0,
                reason = FilterReason.CANDIDATE_UNCONFIRMED.wireValue,
                accuracyMeters = 30f,
            ),
            rejectReason = TrackPointRejectReason.JUMP, eventTimeMs = 55_000L, nowMs = 55_000L,
        )

        assertTrue(cap1 is AutoMotionRejectHandling.Preserved)
        assertTrue(cap2 is AutoMotionRejectHandling.Evidence)
        assertEquals(TrackingMotionMode.WALKING, (cap2 as AutoMotionRejectHandling.Evidence).output.state.mode)
        assertTrue(
            "candidate-unconfirmed fix with zeroed effective distance/implied speed should " +
                "still complete the HANDSHAKE via rawDistanceMeters; got $relocation1",
            relocation1 is AutoMotionRejectHandling.Evidence,
        )
        assertEquals(TrackingMotionMode.DRIVING, (relocation1 as AutoMotionRejectHandling.Evidence).output.state.mode)
    }

    private fun staleRelocationMetrics(
        lat: Double,
        lon: Double,
        rawSpeedMps: Double,
        elapsedSeconds: Double,
        reason: String,
        accuracyMeters: Float,
    ): TrackPointDecisionMetrics {
        return TrackPointDecisionMetrics(
            rawDistanceMeters = rawSpeedMps * elapsedSeconds,
            effectiveDistanceMeters = 0.0,
            elapsedSeconds = elapsedSeconds,
            impliedSpeedMps = 0.0,
            accuracyMeters = accuracyMeters,
            rollingAverageStepMeters = 0.0,
            capCandidateMeters = 100.0,
            decision = "held",
            reason = reason,
            rawLatitude = lat,
            rawLongitude = lon,
        )
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
