package com.geovault.tracker.services

import com.geovault.tracker.TrackingLocationPolicy
import com.geovault.tracker.location.StationaryPingController
import com.geovault.tracker.location.PositioningRecoveryConfig
import com.geovault.tracker.policy.filter.LocationFilterConfig
import com.geovault.tracker.settings.TrackerSettings

data class TrackerPositioningRuntimeContext(
    val settings: TrackerSettings,
    val activeMotionMode: TrackingMotionMode,
    val locationIntervalSec: Long,
    val distanceFilterMeters: Float,
    val pointFreshnessIntervalSec: Long,
    val effectiveAccuracyThresholdMeters: Float,
    val filterConfig: LocationFilterConfig,
    val recoveryConfig: PositioningRecoveryConfig,
    val stationaryRadiusMeters: Float,
    val stationaryAccuracyCeilingMeters: Float,
    val stationaryProbeIntervalMs: Long,
) {
    val maxLocalPointGapMs: Long
        get() = recoveryConfig.maxLocalPointGapMs

    companion object {
        fun build(
            settings: TrackerSettings,
            activeMotionMode: TrackingMotionMode,
            effectiveDistanceFilterMeters: Float,
            localPointMaxGapMs: Long,
        ): TrackerPositioningRuntimeContext {
            val preset = PositioningPresets.forMotionMode(activeMotionMode)
            val filterConfig = TrackingPolicyProfiles.ingestConfig(
                maxAccuracyMeters = preset.accuracyThresholdMeters,
                motionMode = activeMotionMode,
                isMockLocation = false,
            )
            return TrackerPositioningRuntimeContext(
                settings = settings,
                activeMotionMode = activeMotionMode,
                locationIntervalSec = preset.locationIntervalSec,
                distanceFilterMeters = effectiveDistanceFilterMeters,
                pointFreshnessIntervalSec = preset.locationIntervalSec,
                effectiveAccuracyThresholdMeters = preset.accuracyThresholdMeters,
                filterConfig = filterConfig,
                recoveryConfig = preset.recoveryConfig(localPointMaxGapMs),
                stationaryRadiusMeters = TrackingLocationPolicy.DEFAULT_STATIONARY_RADIUS_METERS,
                stationaryAccuracyCeilingMeters = TrackingLocationPolicy.STATIONARY_ACCURACY_CEILING_METERS,
                stationaryProbeIntervalMs = StationaryPingController.DEFAULT_INTERVAL_MS,
            )
        }
    }
}
