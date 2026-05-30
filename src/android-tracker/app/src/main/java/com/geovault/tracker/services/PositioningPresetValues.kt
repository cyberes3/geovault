package com.geovault.tracker.services

import com.geovault.tracker.TrackingLocationPolicy
import com.geovault.tracker.location.PositioningRecoveryConfig
import com.geovault.tracker.policy.filter.MotionProfileTuning
import com.geovault.tracker.settings.TrackerSettings

data class PositioningPresetValues(
    val motionMode: TrackingMotionMode,
    val locationIntervalSec: Long,
    val distanceFilterMeters: Float,
    val accuracyThresholdMeters: Float,
    val filterTuning: MotionProfileTuning,
    val recoverySpeedCapMps: Float,
) {
    fun withDensity(density: PositioningDensity): PositioningPresetValues {
        if (density == PositioningDensity.Normal) return this
        return copy(
            locationIntervalSec = density.scaleIntervalSec(locationIntervalSec),
            distanceFilterMeters = density.scaleDistanceMeters(distanceFilterMeters),
        )
    }

    fun recoveryConfig(maxLocalPointGapMs: Long): PositioningRecoveryConfig {
        return PositioningRecoveryConfig(
            maxLocalPointGapMs = maxLocalPointGapMs,
            recoverySpeedCapMps = recoverySpeedCapMps,
        )
    }
}

object PositioningPresets {
    fun forMotionMode(
        motionMode: TrackingMotionMode,
        density: PositioningDensity = PositioningDensity.Normal,
    ): PositioningPresetValues {
        return basePreset(motionMode).withDensity(density)
    }

    private fun basePreset(motionMode: TrackingMotionMode): PositioningPresetValues {
        return when (motionMode) {
            TrackingMotionMode.WALKING -> PositioningPresetValues(
                motionMode = motionMode,
                locationIntervalSec = TrackingLocationPolicy.WALKING_INTERVAL_SEC,
                distanceFilterMeters = TrackingLocationPolicy.WALKING_DISTANCE_FILTER_METERS,
                accuracyThresholdMeters = TrackerSettings.INTERNAL_ACCURACY_FILTER_METERS,
                filterTuning = MotionProfileTuning.Walking,
                recoverySpeedCapMps = MotionProfileTuning.Walking.maxImpliedSpeedMps.toFloat(),
            )
            TrackingMotionMode.BIKING -> PositioningPresetValues(
                motionMode = motionMode,
                locationIntervalSec = TrackingLocationPolicy.BIKING_INTERVAL_SEC,
                distanceFilterMeters = TrackingLocationPolicy.BIKING_DISTANCE_FILTER_METERS,
                accuracyThresholdMeters = TrackerSettings.INTERNAL_ACCURACY_FILTER_METERS,
                filterTuning = MotionProfileTuning.Biking,
                recoverySpeedCapMps = MotionProfileTuning.Biking.maxImpliedSpeedMps.toFloat(),
            )
            TrackingMotionMode.DRIVING -> PositioningPresetValues(
                motionMode = motionMode,
                locationIntervalSec = TrackingLocationPolicy.DRIVING_INTERVAL_SEC,
                distanceFilterMeters = TrackingLocationPolicy.DRIVING_DISTANCE_FILTER_METERS,
                accuracyThresholdMeters = TrackerSettings.INTERNAL_ACCURACY_FILTER_METERS,
                filterTuning = MotionProfileTuning.Driving,
                recoverySpeedCapMps = MotionProfileTuning.Driving.maxImpliedSpeedMps.toFloat(),
            )
        }
    }
}
