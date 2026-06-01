package com.geovault.tracker.positioning

import com.geovault.tracker.TrackingLocationPolicy
import com.geovault.tracker.location.StationaryPingController
import com.geovault.tracker.location.PositioningRecoveryConfig
import com.geovault.tracker.policy.filter.LocationFilterConfig
import com.geovault.tracker.positioning.config.PositioningDensity
import com.geovault.tracker.positioning.config.PositioningPolicyConfig
import com.geovault.tracker.positioning.config.PositioningPresets
import com.geovault.tracker.services.TrackingMotionMode
import com.geovault.tracker.settings.TrackerSettings

data class PositioningContext(
    val settings: TrackerSettings,
    val activeMotionMode: TrackingMotionMode,
    val collectionPace: RecordingPace,
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
            collectionPace: RecordingPace,
        ): PositioningContext {
            val density = PositioningDensity.from(settings)
            val preset = PositioningPresets.forMotionMode(activeMotionMode, density)
            val filterConfig = PositioningPolicyConfig.ingestConfig(
                maxAccuracyMeters = preset.accuracyThresholdMeters,
                motionMode = activeMotionMode,
            )
            return PositioningContext(
                settings = settings,
                activeMotionMode = activeMotionMode,
                collectionPace = collectionPace,
                locationIntervalSec = preset.locationIntervalSec,
                distanceFilterMeters = effectiveDistanceFilterMeters,
                pointFreshnessIntervalSec = preset.locationIntervalSec,
                effectiveAccuracyThresholdMeters = preset.accuracyThresholdMeters,
                filterConfig = filterConfig,
                recoveryConfig = preset.recoveryConfig(localPointMaxGapMs),
                stationaryRadiusMeters = TrackingLocationPolicy.DEFAULT_STATIONARY_RADIUS_METERS,
                stationaryAccuracyCeilingMeters = TrackingLocationPolicy.STATIONARY_ACCURACY_CEILING_METERS,
                stationaryProbeIntervalMs = density.scaleDurationMs(
                    StationaryPingController.DEFAULT_INTERVAL_MS
                ),
            )
        }
    }
}
