package com.geovault.tracker.positioning

import com.geovault.tracker.policy.TrackPointRejectReason
import com.geovault.tracker.positioning.FastLockTriggerInput
import com.geovault.tracker.positioning.LocationUpdateGate
import com.geovault.tracker.positioning.RuntimeLocationGateInput
import com.geovault.tracker.positioning.config.GpsRuntimeState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationUpdateGateTest {

    @Test
    fun shouldProcessLocationUpdate_blocksPausedWhenNotBypassed() {
        val allowed = LocationUpdateGate.shouldProcessLocationUpdate(
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
        val allowed = LocationUpdateGate.shouldProcessLocationUpdate(
            RuntimeLocationGateInput(
                isTracking = true,
                gpsState = GpsRuntimeState.PAUSED_FOR_MOTION,
                allowWhenGpsPaused = true
            )
        )
        assertTrue(allowed)
    }

    @Test
    fun shouldProcessLocationUpdate_blocksWhenNotTracking() {
        assertFalse(
            LocationUpdateGate.shouldProcessLocationUpdate(
                RuntimeLocationGateInput(
                    isTracking = false,
                    gpsState = GpsRuntimeState.RUNNING,
                    allowWhenGpsPaused = false,
                ),
            ),
        )
    }

    @Test
    fun shouldProcessLocationUpdate_blocksWaitingForProviderUnlessBypassed() {
        assertFalse(
            LocationUpdateGate.shouldProcessLocationUpdate(
                RuntimeLocationGateInput(
                    isTracking = true,
                    gpsState = GpsRuntimeState.WAITING_FOR_PROVIDER,
                    allowWhenGpsPaused = false,
                ),
            ),
        )
        assertTrue(
            LocationUpdateGate.shouldProcessLocationUpdate(
                RuntimeLocationGateInput(
                    isTracking = true,
                    gpsState = GpsRuntimeState.WAITING_FOR_PROVIDER,
                    allowWhenGpsPaused = true,
                ),
            ),
        )
    }

    @Test
    fun shouldAttemptFastLock_rejectsNonAccuracyReasonWithMeasuredAccuracy() {
        val shouldStart = LocationUpdateGate.shouldAttemptFastLock(
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
        val shouldStart = LocationUpdateGate.shouldAttemptFastLock(
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
