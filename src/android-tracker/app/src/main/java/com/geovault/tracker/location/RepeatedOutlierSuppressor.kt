package com.geovault.tracker.location

import android.location.Location
import kotlin.math.floor

data class RepeatedOutlierDecision(
    val suppress: Boolean,
    val reason: String,
    val repeatCount: Int,
)

/**
 * Recognizes recurring, far-away, low-accuracy fixes from fused location.
 *
 * The [com.geovault.tracker.policy.filter.LocationFilter] still rejects each
 * sample (e.g. `low-accuracy`, `implied-speed`). This service-layer guard
 * It stops the same ghost fix from also driving fallback timers or fast-lock churn.
 */
class RepeatedOutlierSuppressor(
    private val configProvider: () -> PositioningRecoveryConfig = {
        PositioningRecoveryConfig(
            maxLocalPointGapMs = PositioningRecoveryConfig.DEFAULT_MAX_LOCAL_POINT_GAP_MS,
            recoverySpeedCapMps = Float.MAX_VALUE,
        )
    },
) {
    private data class Fingerprint(
        val latBucket: Int,
        val lonBucket: Int,
        val accuracyBucket: Int,
    )

    private var lastFingerprint: Fingerprint? = null
    private var repeatCount: Int = 0
    private var lastSeenAtMs: Long = 0L

    fun reset() {
        lastFingerprint = null
        repeatCount = 0
        lastSeenAtMs = 0L
    }

    fun evaluate(
        candidate: Location,
        anchor: Location?,
        effectiveAccuracyThresholdMeters: Float,
        nowMs: Long,
    ): RepeatedOutlierDecision {
        if (!isSuppressible(candidate, anchor, effectiveAccuracyThresholdMeters)) {
            resetIfExpired(nowMs)
            return RepeatedOutlierDecision(suppress = false, reason = "not_suppressible", repeatCount = 0)
        }
        val fingerprint = fingerprint(candidate)
        if (
            lastFingerprint == fingerprint &&
            nowMs - lastSeenAtMs <= configProvider().repeatedOutlierRepeatWindowMs
        ) {
            repeatCount++
        } else {
            lastFingerprint = fingerprint
            repeatCount = 1
        }
        lastSeenAtMs = nowMs
        val suppress = repeatCount >= configProvider().repeatedOutlierSuppressAfterCount
        return RepeatedOutlierDecision(
            suppress = suppress,
            reason = if (suppress) "repeated-low-accuracy-outlier" else "first-low-accuracy-outlier",
            repeatCount = repeatCount,
        )
    }

    private fun isSuppressible(
        candidate: Location,
        anchor: Location?,
        effectiveAccuracyThresholdMeters: Float,
    ): Boolean {
        if (!candidate.hasAccuracy()) return false
        val minAccuracy = maxOf(
            configProvider().repeatedOutlierMinAccuracyMeters,
            effectiveAccuracyThresholdMeters * configProvider().repeatedOutlierAccuracyThresholdMultiplier,
        )
        if (candidate.accuracy < minAccuracy) return false
        val distanceFromAnchor = anchor?.distanceTo(candidate) ?: return false
        return distanceFromAnchor >= configProvider().repeatedOutlierMinDistanceFromAnchorMeters
    }

    private fun resetIfExpired(nowMs: Long) {
        if (lastSeenAtMs > 0L && nowMs - lastSeenAtMs > configProvider().repeatedOutlierRepeatWindowMs) {
            reset()
        }
    }

    private fun fingerprint(location: Location): Fingerprint {
        return Fingerprint(
            latBucket = floor(location.latitude / configProvider().repeatedOutlierCoordinateBucketDegrees).toInt(),
            lonBucket = floor(location.longitude / configProvider().repeatedOutlierCoordinateBucketDegrees).toInt(),
            accuracyBucket = floor(location.accuracy / configProvider().repeatedOutlierAccuracyBucketMeters).toInt(),
        )
    }
}
