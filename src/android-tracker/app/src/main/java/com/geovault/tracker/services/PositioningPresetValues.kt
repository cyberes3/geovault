package com.geovault.tracker.services

import com.geovault.tracker.TrackingLocationPolicy
import com.geovault.tracker.location.PositioningRecoveryConfig
import com.geovault.tracker.policy.filter.MotionProfileTuning

data class PositioningPresetValues(
    val motionMode: TrackingMotionMode,
    val locationIntervalSec: Long,
    val distanceFilterMeters: Float,
    val filterTuning: MotionProfileTuning,
    val recoverySpeedCapMps: Float,
) {
    fun recoveryConfig(maxLocalPointGapMs: Long): PositioningRecoveryConfig {
        return PositioningRecoveryConfig(
            maxLocalPointGapMs = maxLocalPointGapMs,
            recoverySpeedCapMps = recoverySpeedCapMps,
        )
    }
}

object PositioningPresets {
    fun forMotionMode(motionMode: TrackingMotionMode): PositioningPresetValues {
        return when (motionMode) {
            TrackingMotionMode.WALKING -> PositioningPresetValues(
                motionMode = motionMode,
                locationIntervalSec = TrackingLocationPolicy.WALKING_INTERVAL_SEC,
                distanceFilterMeters = TrackingLocationPolicy.WALKING_DISTANCE_FILTER_METERS,
                filterTuning = MotionProfileTuning.Walking,
                recoverySpeedCapMps = 4.5f,
            )
            TrackingMotionMode.BIKING -> PositioningPresetValues(
                motionMode = motionMode,
                locationIntervalSec = TrackingLocationPolicy.BIKING_INTERVAL_SEC,
                distanceFilterMeters = TrackingLocationPolicy.BIKING_DISTANCE_FILTER_METERS,
                filterTuning = MotionProfileTuning.Biking,
                recoverySpeedCapMps = 14f,
            )
            TrackingMotionMode.DRIVING -> PositioningPresetValues(
                motionMode = motionMode,
                locationIntervalSec = TrackingLocationPolicy.DRIVING_INTERVAL_SEC,
                distanceFilterMeters = TrackingLocationPolicy.DRIVING_DISTANCE_FILTER_METERS,
                filterTuning = MotionProfileTuning.Driving,
                recoverySpeedCapMps = 60f,
            )
        }
    }
}
