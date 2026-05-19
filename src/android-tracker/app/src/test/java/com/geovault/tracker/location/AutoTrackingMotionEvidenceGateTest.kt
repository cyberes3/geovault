package com.geovault.tracker.location

import com.geovault.tracker.policy.TrackPointDecisionMetrics
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AutoTrackingMotionEvidenceGateTest {

    @Test
    fun evaluate_sustainedAccurateSpeedCapRejects_emitEvidence() {
        val gate = AutoTrackingMotionEvidenceGate()

        val seed = gate.evaluate(
            metrics = metrics(
                lat = 12.0000,
                lon = -45.0000,
                speedMps = 22.0,
                accuracyMeters = 8f,
            ),
            eventTimeMs = 1_000L,
        )
        val second = gate.evaluate(
            metrics = metrics(
                lat = 12.0000,
                lon = -44.9950,
                speedMps = 21.5,
                accuracyMeters = 9f,
            ),
            eventTimeMs = 21_000L,
        )

        assertNull(seed)
        assertNotNull(second)
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
                    accuracyMeters = 8f,
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
                    accuracyMeters = 8f,
                ),
                eventTimeMs = 21_000L,
            )
        )
        val zigZag = gate.evaluate(
            metrics = metrics(
                lat = 12.0000,
                lon = -45.0000,
                speedMps = 22.0,
                accuracyMeters = 8f,
            ),
            eventTimeMs = 41_000L,
        )

        assertNull(zigZag)
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
