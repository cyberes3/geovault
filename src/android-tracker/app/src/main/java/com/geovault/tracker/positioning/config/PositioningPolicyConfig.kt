package com.geovault.tracker.positioning.config
import com.geovault.tracker.services.TrackingMotionMode

import com.geovault.tracker.policy.filter.LocationFilterConfig

/**
 * Builds internal positioning policy configs from speed-selected presets.
 * User-facing tracking settings are intentionally not part of this path.
 */
object PositioningPolicyConfig {
    private const val MAX_FUTURE_SKEW_MS = 5L * 60L * 1000L
    private const val LOCAL_FRESHNESS_TTL_MS = 120_000L
    private const val FALLBACK_FRESHNESS_TTL_MS = 2L * 60L * 1000L

    const val MOCK_TIMESTAMP_SKEW_TOLERANCE_MS = 5L * 60L * 1000L
    const val LOCAL_STALL_REJECT_STREAK_THRESHOLD = 6L
    const val LOCAL_STALL_REANCHOR_MIN_ANCHOR_AGE_MS = 3L * 60L * 1000L

    fun ingestConfig(
        maxAccuracyMeters: Float,
        motionMode: TrackingMotionMode,
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
}
