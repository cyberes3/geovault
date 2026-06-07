package com.geovault.tracker.location

import com.geovault.tracker.policy.TrackPointDecisionMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AutoTrackingMotionEvidenceGateTest {

    @Test
    fun evaluate_sustainedAccurateSpeedCapRejects_emitEvidence() {
        val gate = AutoTrackingMotionEvidenceGate()

        // Use accuracies above fastEmitAccuracyMeters so the seed is held
        // back for continuity checking, exercising the original
        // two-observation handshake.
        val seed = gate.evaluate(
            metrics = metrics(
                lat = 12.0000,
                lon = -45.0000,
                speedMps = 22.0,
                accuracyMeters = 18f,
            ),
            eventTimeMs = 1_000L,
        )
        val second = gate.evaluate(
            metrics = metrics(
                lat = 12.0000,
                lon = -44.9950,
                speedMps = 21.5,
                accuracyMeters = 18f,
            ),
            eventTimeMs = 21_000L,
        )

        assertNull(seed)
        assertNotNull(second)
        assertEquals(EvidencePath.HANDSHAKE, second!!.path)
    }

    @Test
    fun evaluate_lowAccuracyRejectDoesNotEmitEvidence() {
        val gate = AutoTrackingMotionEvidenceGate()

        val evidence = gate.evaluate(
            metrics = metrics(
                lat = 12.0000,
                lon = -45.0000,
                speedMps = 22.0,
                accuracyMeters = 80f,
            ),
            eventTimeMs = 1_000L,
        )

        assertNull(evidence)
    }

    @Test
    fun evaluate_unsupportedReasonDoesNotEmitEvidence() {
        val gate = AutoTrackingMotionEvidenceGate()

        val evidence = gate.evaluate(
            metrics = metrics(
                lat = 12.0000,
                lon = -45.0000,
                speedMps = 22.0,
                accuracyMeters = 8f,
                reason = "low-accuracy",
            ),
            eventTimeMs = 1_000L,
        )

        assertNull(evidence)
    }

    @Test
    fun evaluate_staleObservationWindowDoesNotEmitEvidence() {
        val gate = AutoTrackingMotionEvidenceGate()

        val evidence = gate.evaluate(
            metrics = metrics(
                lat = 12.0000,
                lon = -45.0000,
                speedMps = 22.0,
                accuracyMeters = 8f,
                elapsedSeconds = 70.0,
            ),
            eventTimeMs = 1_000L,
        )

        assertNull(evidence)
    }

    @Test
    fun evaluate_zigZagSequenceDoesNotEmitSecondEvidence() {
        val gate = AutoTrackingMotionEvidenceGate()

        assertNull(
            gate.evaluate(
                metrics = metrics(
                    lat = 12.0000,
                    lon = -45.0000,
                    speedMps = 22.0,
                    accuracyMeters = 18f,
                ),
                eventTimeMs = 1_000L,
            )
        )
        assertNotNull(
            gate.evaluate(
                metrics = metrics(
                    lat = 12.0000,
                    lon = -44.9950,
                    speedMps = 22.0,
                    accuracyMeters = 18f,
                ),
                eventTimeMs = 21_000L,
            )
        )
        val zigZag = gate.evaluate(
            metrics = metrics(
                lat = 12.0000,
                lon = -45.0000,
                speedMps = 22.0,
                accuracyMeters = 18f,
            ),
            eventTimeMs = 41_000L,
        )

        assertNull(zigZag)
    }

    @Test
    fun evaluate_strongFirstFix_emitsImmediately() {
        // Tight accuracy AND a speed clearly above the WALKING upper means
        // the first observation is already conclusive on its own - emit
        // without waiting for a continuity handshake.
        val gate = AutoTrackingMotionEvidenceGate()

        val evidence = gate.evaluate(
            metrics = metrics(
                lat = 12.0000,
                lon = -45.0000,
                speedMps = 22.0,
                accuracyMeters = 4f,
            ),
            eventTimeMs = 1_000L,
        )

        assertNotNull(evidence)
        assertEquals(EvidencePath.FAST_EMIT, evidence!!.path)
    }

    @Test
    fun evaluate_strongFirstFixStillStoresSeedForContinuityCheck() {
        // After a strong fast-emit the seed is stored, so the standard
        // course-reversal continuity guard still applies on subsequent
        // observations.
        val gate = AutoTrackingMotionEvidenceGate()
        assertNotNull(
            gate.evaluate(
                metrics = metrics(
                    lat = 12.0000,
                    lon = -45.0000,
                    speedMps = 22.0,
                    accuracyMeters = 4f,
                ),
                eventTimeMs = 1_000L,
            )
        )
        // Walks east to establish course, then reverses west.
        assertNotNull(
            gate.evaluate(
                metrics = metrics(
                    lat = 12.0000,
                    lon = -44.9950,
                    speedMps = 22.0,
                    accuracyMeters = 4f,
                ),
                eventTimeMs = 21_000L,
            )
        )
        val reversed = gate.evaluate(
            metrics = metrics(
                lat = 12.0000,
                lon = -45.0000,
                speedMps = 22.0,
                accuracyMeters = 4f,
            ),
            eventTimeMs = 41_000L,
        )

        assertNull(reversed)
    }

    /**
     * Mirrors the highway-drive scenario: a stale-relocation-unconfirmed hold
     * interleaves every pair of speed-cap-exceeded fixes. The GPS interval is
     * 20 s, so consecutive cap-evidence observations land at t=0 and t=40 s —
     * the device travels ~1 320 m between them at 33 m/s.
     *
     * With the old code the continuity allowance used metrics.elapsedSeconds (20 s),
     * giving 33 × 20 × 1.5 = 990 m < 1 320 m → continuity failed and the
     * HANDSHAKE never fired. With the fix the allowance uses the actual
     * observation gap (40 s): 33 × 40 × 1.5 = 1 980 m > 1 320 m → passes.
     */
    /**
     * When isContinuous fails (the new fix is geographically inconsistent with
     * the stored prior), the prior context is invalidated. A strong fix in this
     * position should fire FAST_EMIT, identical to the true first-observation path,
     * rather than silently swallowing the evidence.
     */
    @Test
    fun evaluate_discontinuousStrongFix_fastEmitsAfterContextInvalidated() {
        val gate = AutoTrackingMotionEvidenceGate()

        // Seed: weak accuracy — stores observation but no FAST_EMIT
        assertNull(
            gate.evaluate(
                metrics = metrics(lat = 39.0, lon = -104.0, speedMps = 22.0, accuracyMeters = 18f),
                eventTimeMs = 0L,
            )
        )
        // Discontinuous strong fix: jumps far in the wrong direction so isContinuous
        // fails, but accuracy is tight and speed is high → FAST_EMIT should fire.
        val result = gate.evaluate(
            metrics = metrics(lat = 38.0, lon = -105.5, speedMps = 22.0, accuracyMeters = 5f),
            eventTimeMs = 20_000L,
        )

        assertNotNull(result)
        assertEquals(EvidencePath.FAST_EMIT, result!!.path)
    }

    @Test
    fun evaluate_sparseGap_continuityUsesObservationTimestampGap() {
        val gate = AutoTrackingMotionEvidenceGate()

        // seed — accuracy above fastEmitAccuracyMeters so no FAST_EMIT
        val seed = gate.evaluate(
            metrics = metrics(lat = 39.0000, lon = -104.0000, speedMps = 33.0, accuracyMeters = 15f),
            eventTimeMs = 0L,
        )
        // 40 s later (one stale-relocation hold interleaved, per-fix interval still 20 s)
        val second = gate.evaluate(
            metrics = metrics(lat = 39.0119, lon = -104.0000, speedMps = 33.0, accuracyMeters = 15f),
            eventTimeMs = 40_000L,
        )

        assertNull(seed)
        assertNotNull(second)
        assertEquals(EvidencePath.HANDSHAKE, second!!.path)
    }

    @Test
    fun evaluate_strongFirstFixRequiresLowSpeedFloor() {
        // Walking-speed cap rejects (which can only happen if our cap is
        // misconfigured low) must not fast-emit even with perfect accuracy.
        val gate = AutoTrackingMotionEvidenceGate()

        val evidence = gate.evaluate(
            metrics = metrics(
                lat = 12.0000,
                lon = -45.0000,
                speedMps = 4.0,
                accuracyMeters = 3f,
            ),
            eventTimeMs = 1_000L,
        )

        assertNull(evidence)
    }

    private fun metrics(
        lat: Double,
        lon: Double,
        speedMps: Double,
        accuracyMeters: Float,
        reason: String = "speed-cap-exceeded",
        elapsedSeconds: Double = 20.0,
    ): TrackPointDecisionMetrics {
        return TrackPointDecisionMetrics(
            rawDistanceMeters = speedMps * elapsedSeconds,
            effectiveDistanceMeters = speedMps * elapsedSeconds,
            elapsedSeconds = elapsedSeconds,
            impliedSpeedMps = speedMps,
            accuracyMeters = accuracyMeters,
            rollingAverageStepMeters = 0.0,
            capCandidateMeters = 90.0,
            decision = "rejected",
            reason = reason,
            rawLatitude = lat,
            rawLongitude = lon,
        )
    }
}
