package com.geovault.tracker.services
import com.geovault.tracker.positioning.config.GpsRuntimeState

import com.geovault.tracker.policy.TrackPointRejectReason

data class RuntimeLocationGateInput(
    val isTracking: Boolean,
    val gpsState: GpsRuntimeState,
    val allowWhenGpsPaused: Boolean
)

data class FastLockTriggerInput(
    val isTracking: Boolean,
    val isFastGpsLockWindowActive: Boolean,
    val isFastGpsLockPriming: Boolean,
    val gpsState: GpsRuntimeState,
    val rejectReason: TrackPointRejectReason?,
    val measuredAccuracyMeters: Float?,
    val accuracyFilterMeters: Float
)

object TrackingRuntimeOrchestrator {
    @JvmStatic
    fun shouldProcessLocationUpdate(input: RuntimeLocationGateInput): Boolean {
        if (!input.isTracking || input.gpsState == GpsRuntimeState.INACTIVE) return false
        if (input.allowWhenGpsPaused) return true
        return input.gpsState != GpsRuntimeState.PAUSED_FOR_MOTION &&
            input.gpsState != GpsRuntimeState.WAITING_FOR_PROVIDER &&
            input.gpsState != GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED
    }

    @JvmStatic
    fun shouldAttemptFastLock(input: FastLockTriggerInput): Boolean {
        if (
            !input.isTracking ||
            input.isFastGpsLockWindowActive ||
            input.isFastGpsLockPriming ||
            input.gpsState == GpsRuntimeState.PAUSED_FOR_MOTION ||
            input.gpsState == GpsRuntimeState.WAITING_FOR_PROVIDER ||
            input.gpsState == GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED
        ) {
            return false
        }
        if (
            input.rejectReason != null &&
            input.rejectReason != TrackPointRejectReason.BAD_ACCURACY &&
            input.measuredAccuracyMeters != null
        ) {
            return false
        }
        val measured = input.measuredAccuracyMeters ?: return true
        return measured > input.accuracyFilterMeters
    }
}
