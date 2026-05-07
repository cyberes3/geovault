package com.geovault.tracker.policy

import com.geovault.tracker.policy.filter.LocationFilterConfig

/**
 * Defensive clamping for [LocationFilterConfig] values that come from
 * persisted user settings. Filters constructed from these clamped values
 * are guaranteed to produce sane behaviour even if a setting on disk is
 * out of range (e.g. corrupted preference, future schema regression).
 */
object TrackPointPolicyCoercion {
    private const val MIN_MAX_IMPLIED_SPEED_MPS = 1.0
    private const val MAX_MAX_IMPLIED_SPEED_MPS = 200.0
    private const val MIN_MAX_BURST_DISTANCE_METERS = 5.0
    private const val MAX_MAX_BURST_DISTANCE_METERS = 2_000.0
    private const val MIN_BURST_WINDOW_SECONDS = 0.2
    private const val MAX_BURST_WINDOW_SECONDS = 120.0
    private const val MIN_ROLLING_WINDOW_SECONDS = 1.0
    private const val MAX_ROLLING_WINDOW_SECONDS = 60.0
    private const val MIN_TRACKING_ACCURACY_METERS = 1.0
    private const val MAX_TRACKING_ACCURACY_METERS = 10_000.0
    private const val MIN_FUTURE_SKEW_MS = 0L
    private const val MAX_FUTURE_SKEW_MS = 24L * 60L * 60L * 1000L
    private const val MIN_FRESHNESS_TTL_MS = 0L
    private const val MAX_FRESHNESS_TTL_MS = 24L * 60L * 60L * 1000L

    fun sanitize(config: LocationFilterConfig): LocationFilterConfig {
        return config.copy(
            maxImpliedSpeedMps = config.maxImpliedSpeedMps.coerceIn(
                MIN_MAX_IMPLIED_SPEED_MPS,
                MAX_MAX_IMPLIED_SPEED_MPS,
            ),
            maxBurstDistanceMeters = config.maxBurstDistanceMeters.coerceIn(
                MIN_MAX_BURST_DISTANCE_METERS,
                MAX_MAX_BURST_DISTANCE_METERS,
            ),
            burstWindowSeconds = config.burstWindowSeconds.coerceIn(
                MIN_BURST_WINDOW_SECONDS,
                MAX_BURST_WINDOW_SECONDS,
            ),
            rollingWindowSeconds = config.rollingWindowSeconds.coerceIn(
                MIN_ROLLING_WINDOW_SECONDS,
                MAX_ROLLING_WINDOW_SECONDS,
            ),
            trackingAccuracyThresholdMeters = config.trackingAccuracyThresholdMeters.coerceIn(
                MIN_TRACKING_ACCURACY_METERS,
                MAX_TRACKING_ACCURACY_METERS,
            ),
            maxFutureSkewMs = config.maxFutureSkewMs.coerceIn(MIN_FUTURE_SKEW_MS, MAX_FUTURE_SKEW_MS),
            freshnessTtlMs = config.freshnessTtlMs.coerceIn(MIN_FRESHNESS_TTL_MS, MAX_FRESHNESS_TTL_MS),
        )
    }
}
