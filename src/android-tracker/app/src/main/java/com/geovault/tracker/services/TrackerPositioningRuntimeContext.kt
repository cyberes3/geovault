package com.geovault.tracker.services

import com.geovault.tracker.TrackingLocationPolicy
import com.geovault.tracker.location.PositioningRecoveryConfig
import com.geovault.tracker.location.StationaryPingController
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
            val profileIntervalSec = TrackingLocationPolicy
                .getProfileParams(activeMotionMode.profileIndex)
                .first
            val locationIntervalSec = if (settings.autoTrackingMode) {
                profileIntervalSec
            } else {
                settings.loggingIntervalSec
            }
            val filterConfig = TrackingPolicyProfiles.ingestConfig(
                maxAccuracyMeters = settings.accuracyFilterMeters,
                motionMode = activeMotionMode,
                isMockLocation = false,
            )
            return TrackerPositioningRuntimeContext(
                settings = settings,
                activeMotionMode = activeMotionMode,
                locationIntervalSec = locationIntervalSec,
                distanceFilterMeters = effectiveDistanceFilterMeters,
                pointFreshnessIntervalSec = profileIntervalSec,
                effectiveAccuracyThresholdMeters = settings.accuracyFilterMeters,
                filterConfig = filterConfig,
                recoveryConfig = PositioningRecoveryConfig.fromMotionMode(
                    motionMode = activeMotionMode,
                    maxLocalPointGapMs = localPointMaxGapMs,
                ),
                stationaryRadiusMeters = TrackingLocationPolicy.DEFAULT_STATIONARY_RADIUS_METERS,
                stationaryAccuracyCeilingMeters = TrackingLocationPolicy.STATIONARY_ACCURACY_CEILING_METERS,
                stationaryProbeIntervalMs = StationaryPingController.DEFAULT_INTERVAL_MS,
            )
        }
    }
}
