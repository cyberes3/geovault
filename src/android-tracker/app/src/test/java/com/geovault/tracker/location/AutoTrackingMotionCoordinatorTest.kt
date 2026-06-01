package com.geovault.tracker.location

import com.geovault.tracker.policy.TrackPointDecisionMetrics
import com.geovault.tracker.policy.filter.LocationFilterReasons
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
                reason = LocationFilterReasons.SPEED_CAP_EXCEEDED,
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
                reason = LocationFilterReasons.STALE_RELOCATION_UNCONFIRMED,
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
                reason = LocationFilterReasons.SPEED_CAP_EXCEEDED,
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

    private fun metrics(
        lat: Double,
        lon: Double,
        speedMps: Double,
        reason: String,
    ): TrackPointDecisionMetrics {
        return TrackPointDecisionMetrics(
            rawDistanceMeters = speedMps * 20.0,
            effectiveDistanceMeters = speedMps * 20.0,
            elapsedSeconds = 20.0,
            impliedSpeedMps = speedMps,
            accuracyMeters = 8f,
            rollingAverageStepMeters = 0.0,
            capCandidateMeters = 100.0,
            decision = "rejected",
            reason = reason,
            rawLatitude = lat,
            rawLongitude = lon,
        )
    }
}
