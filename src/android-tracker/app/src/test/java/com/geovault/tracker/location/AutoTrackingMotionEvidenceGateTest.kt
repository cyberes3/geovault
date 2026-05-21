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
