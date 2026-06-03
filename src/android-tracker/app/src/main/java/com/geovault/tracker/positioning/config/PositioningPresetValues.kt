package com.geovault.tracker.positioning.config
import com.geovault.tracker.services.TrackingMotionMode

import com.geovault.tracker.TrackingLocationPolicy
import com.geovault.tracker.location.PositioningRecoveryConfig
import com.geovault.tracker.policy.filter.MotionProfileTuning
import com.geovault.tracker.settings.TrackerSettings

/**
 * Top-level contract for GeoVault positioning profiles.
 *
 * Profiles are only preconfigured speed-mode settings. They provide the
 * default values used to build a [com.geovault.tracker.positioning.PositioningContext]:
 * provider cadence, distance filter, accuracy threshold, generic filter
 * plausibility limits, recovery defaults, and stationary / elasticity request
 * defaults. They are not behavior switches.
 *
 * Do not fix field reports by adding WALKING / BIKING / DRIVING branches in
 * ingest, recovery, filtering, stationary detection, or fallback code. A
 * walk -> short drive -> walk route, a traffic jam, a parking-lot loop, or any
 * other scenario must be handled by the generic positioning pipeline. If a
 * numeric value truly belongs to a speed mode, add it here as a typed preset
 * value and thread it through PositioningContext. If the algorithm itself is
 * wrong, fix the algorithm once for all profiles.
 *
 * Resolve presets into [com.geovault.tracker.positioning.PositioningContext]
 * once per fix. Ingest, recovery, and collection must read from that context
 * instead of re-resolving presets at call sites. Profiles should never become
 * a place to hide route-specific tuning.
 */
data class PositioningPresetValues(
    val motionMode: TrackingMotionMode,
    val locationIntervalSec: Long,
    val distanceFilterMeters: Float,
    val accuracyThresholdMeters: Float,
    val filterTuning: MotionProfileTuning,
    val recoverySpeedCapMps: Float,
    val stationaryRadiusMeters: Float = TrackingLocationPolicy.DEFAULT_STATIONARY_RADIUS_METERS,
    val stationaryAccuracyCeilingMeters: Float = TrackingLocationPolicy.STATIONARY_ACCURACY_CEILING_METERS,
    val elasticityConfig: PositioningElasticityConfig = PositioningElasticityConfig.Default,
    val fastLockConfig: PositioningFastLockConfig = PositioningFastLockConfig.Default,
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

data class PositioningElasticityConfig(
    val speedBucketSizeMps: Float,
    val multiplier: Float,
    val maxSpeedBucket: Int,
    val maxDistanceFilterMeters: Float,
    val reapplyDistanceDeltaMeters: Float,
) {
    companion object {
        val Default: PositioningElasticityConfig = PositioningElasticityConfig(
            speedBucketSizeMps = 5f,
            multiplier = 0.35f,
            maxSpeedBucket = 8,
            maxDistanceFilterMeters = 10_000f,
            reapplyDistanceDeltaMeters = 0.5f,
        )
    }
}

data class PositioningFastLockConfig(
    val windowMs: Long,
    val minSamples: Int,
    val earlyExitMinSamples: Int,
    val maxLastLocationAgeMs: Long,
    val maxSampleAgeMs: Long,
    val summaryIntervalMs: Long,
    val autoMotionSuppressWindowMs: Long,
) {
    companion object {
        val Default: PositioningFastLockConfig = PositioningFastLockConfig(
            windowMs = 60_000L,
            minSamples = 3,
            earlyExitMinSamples = 2,
            maxLastLocationAgeMs = 30_000L,
            maxSampleAgeMs = 30_000L,
            summaryIntervalMs = 30_000L,
            autoMotionSuppressWindowMs = 15_000L,
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
