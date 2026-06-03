package com.geovault.tracker.location

import com.geovault.tracker.TrackingLocationPolicy
import com.geovault.tracker.policy.filter.FilterReason

data class PositioningRecoveryConfig(
    val maxLocalPointGapMs: Long,
    val freshnessProbeWindowMs: Long = DEFAULT_FRESHNESS_PROBE_WINDOW_MS,
    val minPromotableProbeFixes: Int = DEFAULT_MIN_PROMOTABLE_PROBE_FIXES,
    val freshnessRecoveryHoldReasons: Set<FilterReason> = FilterReason.freshnessRecoveryHolds,
    val anchoredRecoveryAccuracyCeilingMeters: Float = TrackingLocationPolicy.STATIONARY_ACCURACY_CEILING_METERS,
    val recoverySpeedCapMps: Float,
    val repeatedOutlierMinAccuracyMeters: Float = DEFAULT_REPEATED_OUTLIER_MIN_ACCURACY_METERS,
    val repeatedOutlierMinDistanceFromAnchorMeters: Float = DEFAULT_REPEATED_OUTLIER_MIN_DISTANCE_FROM_ANCHOR_METERS,
    val repeatedOutlierAccuracyThresholdMultiplier: Float = DEFAULT_REPEATED_OUTLIER_ACCURACY_THRESHOLD_MULTIPLIER,
    val repeatedOutlierCoordinateBucketDegrees: Double = DEFAULT_REPEATED_OUTLIER_COORDINATE_BUCKET_DEGREES,
    val repeatedOutlierAccuracyBucketMeters: Float = DEFAULT_REPEATED_OUTLIER_ACCURACY_BUCKET_METERS,
    val repeatedOutlierSuppressAfterCount: Int = DEFAULT_REPEATED_OUTLIER_SUPPRESS_AFTER_COUNT,
    val repeatedOutlierRepeatWindowMs: Long = DEFAULT_REPEATED_OUTLIER_REPEAT_WINDOW_MS,
    val fallbackDuplicateTimeDeltaMs: Long = DEFAULT_FALLBACK_DUPLICATE_TIME_DELTA_MS,
    val fallbackDuplicateDistanceMeters: Float = DEFAULT_FALLBACK_DUPLICATE_DISTANCE_METERS,
) {
    companion object {
        const val DEFAULT_MAX_LOCAL_POINT_GAP_MS = 90_000L
        const val DEFAULT_FRESHNESS_PROBE_WINDOW_MS = 90_000L
        const val DEFAULT_MIN_PROMOTABLE_PROBE_FIXES = 2
        const val DEFAULT_FALLBACK_DUPLICATE_TIME_DELTA_MS = 1_000L
        const val DEFAULT_FALLBACK_DUPLICATE_DISTANCE_METERS = 5f
        const val DEFAULT_REPEATED_OUTLIER_MIN_ACCURACY_METERS = 1_000f
        const val DEFAULT_REPEATED_OUTLIER_MIN_DISTANCE_FROM_ANCHOR_METERS = 1_000f
        const val DEFAULT_REPEATED_OUTLIER_ACCURACY_THRESHOLD_MULTIPLIER = 4f
        const val DEFAULT_REPEATED_OUTLIER_COORDINATE_BUCKET_DEGREES = 0.001
        const val DEFAULT_REPEATED_OUTLIER_ACCURACY_BUCKET_METERS = 250f
        const val DEFAULT_REPEATED_OUTLIER_SUPPRESS_AFTER_COUNT = 2
        const val DEFAULT_REPEATED_OUTLIER_REPEAT_WINDOW_MS = 10L * 60_000L
        val DEFAULT_FRESHNESS_RECOVERY_HOLD_REASONS: Set<FilterReason> = FilterReason.freshnessRecoveryHolds
    }
}
