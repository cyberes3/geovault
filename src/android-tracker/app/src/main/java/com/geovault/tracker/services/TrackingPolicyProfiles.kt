package com.geovault.tracker.services

import com.geovault.tracker.policy.filter.LocationFilterConfig

/**
 * The motion-mode profile only affects the [android.location.LocationRequest]
 * (interval / distance-filter) and the user-facing accuracy threshold. The
 * positioning filter pipeline (RSS distance, accCap, kinCap, Kalman,
 * outlier policy) is profile-independent so the same physics-based
 * filter runs at every speed.
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
        @Suppress("UNUSED_PARAMETER") motionMode: TrackingMotionMode,
        @Suppress("UNUSED_PARAMETER") isMockLocation: Boolean,
    ): LocationFilterConfig {
        return LocationFilterConfig.Default.copy(
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
