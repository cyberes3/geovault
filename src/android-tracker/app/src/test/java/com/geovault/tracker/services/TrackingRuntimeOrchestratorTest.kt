package com.geovault.tracker.services
import com.geovault.tracker.positioning.config.GpsRuntimeState

import com.geovault.tracker.policy.TrackPointRejectReason
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingRuntimeOrchestratorTest {

    @Test
    fun shouldProcessLocationUpdate_blocksPausedWhenNotBypassed() {
        val allowed = TrackingRuntimeOrchestrator.shouldProcessLocationUpdate(
            RuntimeLocationGateInput(
                isTracking = true,
                gpsState = GpsRuntimeState.PAUSED_FOR_MOTION,
                allowWhenGpsPaused = false
            )
        )
        assertFalse(allowed)
    }

    @Test
    fun shouldProcessLocationUpdate_allowsPausedWhenBypassed() {
        val allowed = TrackingRuntimeOrchestrator.shouldProcessLocationUpdate(
            RuntimeLocationGateInput(
                isTracking = true,
                gpsState = GpsRuntimeState.PAUSED_FOR_MOTION,
                allowWhenGpsPaused = true
            )
        )
        assertTrue(allowed)
    }

    @Test
    fun shouldAttemptFastLock_rejectsNonAccuracyReasonWithMeasuredAccuracy() {
        val shouldStart = TrackingRuntimeOrchestrator.shouldAttemptFastLock(
            FastLockTriggerInput(
                isTracking = true,
                isFastGpsLockWindowActive = false,
                isFastGpsLockPriming = false,
                gpsState = GpsRuntimeState.RUNNING,
                rejectReason = TrackPointRejectReason.JUMP,
                measuredAccuracyMeters = 100f,
                accuracyFilterMeters = 25f
            )
        )
        assertFalse(shouldStart)
    }

    @Test
    fun shouldAttemptFastLock_acceptsBadAccuracyReason() {
        val shouldStart = TrackingRuntimeOrchestrator.shouldAttemptFastLock(
            FastLockTriggerInput(
                isTracking = true,
                isFastGpsLockWindowActive = false,
                isFastGpsLockPriming = false,
                gpsState = GpsRuntimeState.RUNNING,
                rejectReason = TrackPointRejectReason.BAD_ACCURACY,
                measuredAccuracyMeters = 100f,
                accuracyFilterMeters = 25f
            )
        )
        assertTrue(shouldStart)
    }
}
