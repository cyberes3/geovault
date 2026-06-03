package com.geovault.tracker.location

import com.geovault.tracker.policy.TrackPointDecisionMetrics
import com.geovault.tracker.services.TrackingMotionMode
import com.geovault.tracker.tracking.TrackingServiceConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficJamModeRecoveryTest {

    @Test
    fun recordedCapPair_promotesToDrivingDespitePeriodicTicksBetweenSamples() {
        val engine = AutoTrackingMotionEngine()
        val coordinator = AutoTrackingMotionCoordinator(
            engine = engine,
            evidenceGate = AutoTrackingMotionEvidenceGate(),
            streakPreserveWindowMs = TrackingServiceConstants.AUTO_MOTION_CAP_EVIDENCE_STREAK_PRESERVE_WINDOW_MS,
        )
        engine.reset(nowMs = TrafficJamModeRecoveryFixture.walkingDemotionAccept.wallNowMs())

        val cap1 = TrafficJamModeRecoveryFixture.highwayCapRejects[0]
        val first = coordinator.onRejectedOrHeld(
            metrics = metricsFrom(cap1),
            rejectReason = null,
            eventTimeMs = cap1.gpsTimeMs,
            nowMs = cap1.wallNowMs(),
        )
        assertTrue(first is AutoMotionRejectHandling.Evidence)
        val firstEvidence = first as AutoMotionRejectHandling.Evidence
        assertEquals(TrackingMotionMode.WALKING, firstEvidence.output.state.mode)
        assertEquals(1, engine.snapshot().consecutiveAboveUpper)
        assertEquals(24.351841f, firstEvidence.evidence.speedMps, 0.001f)

        var tickMs = cap1.wallNowMs() + 5_000L
        while (tickMs < TrafficJamModeRecoveryFixture.highwayCapRejects[1].wallNowMs()) {
            engine.onTick(nowMs = tickMs)
            assertEquals(
                "promotion streak must survive periodic decay ticks between recorded caps",
                1,
                engine.snapshot().consecutiveAboveUpper,
            )
            tickMs += 5_000L
        }

        val cap2 = TrafficJamModeRecoveryFixture.highwayCapRejects[1]
        val second = coordinator.onRejectedOrHeld(
            metrics = metricsFrom(cap2),
            rejectReason = null,
            eventTimeMs = cap2.gpsTimeMs,
            nowMs = cap2.wallNowMs(),
        )
        assertTrue(second is AutoMotionRejectHandling.Evidence)
        val secondEvidence = second as AutoMotionRejectHandling.Evidence
        assertEquals(TrackingMotionMode.DRIVING, secondEvidence.output.state.mode)
        assertTrue(secondEvidence.output.modeChanged)
        assertEquals(TransitionPath.SKIP_TO_DRIVING, secondEvidence.output.transitionPath)
        assertEquals(31.552227f, secondEvidence.evidence.speedMps, 0.001f)
    }

    @Test
    fun periodicTick_doesNotClearPromotionStreakWhileDecayingSpeed() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        engine.onMotionEvidence(
            speedMps = 24.351841f,
            eventTimeMs = 1_000L,
            confidence = AutoTrackingMotionEvidenceConfidence.High,
        )
        assertEquals(1, engine.snapshot().consecutiveAboveUpper)

        engine.onTick(nowMs = 200_000L)

        assertEquals(1, engine.snapshot().consecutiveAboveUpper)
        assertTrue(engine.snapshot().smoothedSpeedMps < 24.351841f)
    }

    private fun metricsFrom(fix: TrafficJamModeRecoveryFixture.ReplayFix): TrackPointDecisionMetrics {
        return TrackPointDecisionMetrics(
            rawDistanceMeters = fix.rawDistanceMeters,
            effectiveDistanceMeters = fix.effectiveDistanceMeters,
            elapsedSeconds = fix.elapsedSeconds,
            impliedSpeedMps = fix.impliedSpeedMps,
            accuracyMeters = fix.accuracy,
            rollingAverageStepMeters = 0.0,
            capCandidateMeters = 180.0,
            decision = "rejected",
            reason = fix.filterReason,
            rawLatitude = fix.lat,
            rawLongitude = fix.lon,
        )
    }

    private fun TrafficJamModeRecoveryFixture.ReplayFix.wallNowMs(): Long =
        TrafficJamModeRecoveryFixture.wallNowMs(this)
}
