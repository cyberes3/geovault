package com.geovault.tracker.services

import com.geovault.tracker.policy.filter.LocationFilterConfig

/**
 * Builds positioning policy config from the active internal speed preset.
 * User-facing tracking profiles are intentionally not part of this path.
 */
object TrackingPolicyProfiles {
    private const val MAX_FUTURE_SKEW_MS = 5L * 60L * 1000L
    private const val LOCAL_FRESHNESS_TTL_MS = 120_000L
    private const val FALLBACK_FRESHNESS_TTL_MS = 2L * 60L * 1000L

    const val MOCK_TIMESTAMP_SKEW_TOLERANCE_MS = 5L * 60L * 1000L
    const val LOCAL_STALL_REJECT_STREAK_THRESHOLD = 6L
    const val LOCAL_STALL_REANCHOR_MIN_ANCHOR_AGE_MS = 3L * 60L * 1000L

    fun ingestConfig(
        maxAccuracyMeters: Float,
        motionMode: TrackingMotionMode,
        @Suppress("UNUSED_PARAMETER") isMockLocation: Boolean,
    ): LocationFilterConfig {
        val tuning = PositioningPresets.forMotionMode(motionMode).filterTuning
        return LocationFilterConfig.fromTuning(
            tuning = tuning,
            trackingAccuracyThresholdMeters = maxAccuracyMeters.toDouble(),
            maxFutureSkewMs = MAX_FUTURE_SKEW_MS,
            freshnessTtlMs = LOCAL_FRESHNESS_TTL_MS,
            normalizeSecondsTimestamps = false,
        )
    }

    fun fallbackTransitionConfig(): LocationFilterConfig {
        return LocationFilterConfig.Default.copy(
            maxFutureSkewMs = MAX_FUTURE_SKEW_MS,
            freshnessTtlMs = FALLBACK_FRESHNESS_TTL_MS,
            normalizeSecondsTimestamps = false,
        )
    }

    fun motionModeFromProfileIndex(profileIndex: Int): TrackingMotionMode {
        return when (profileIndex) {
            TrackingMotionMode.WALKING.profileIndex -> TrackingMotionMode.WALKING
            TrackingMotionMode.BIKING.profileIndex -> TrackingMotionMode.BIKING
            TrackingMotionMode.DRIVING.profileIndex -> TrackingMotionMode.DRIVING
            else -> TrackingMotionMode.BIKING
        }
    }
}
