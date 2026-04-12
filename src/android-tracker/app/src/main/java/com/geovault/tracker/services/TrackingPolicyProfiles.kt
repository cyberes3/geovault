package com.geovault.tracker.services

import com.geovault.tracker.policy.TrackPointOutlierPolicy
import com.geovault.tracker.policy.TrackPointPolicyConfig

object TrackingPolicyProfiles {
    private const val MAX_FUTURE_SKEW_MS = 5 * 60 * 1000L

    private const val LOCAL_FRESHNESS_TTL_MS = 120_000L
    const val MOCK_TIMESTAMP_SKEW_TOLERANCE_MS = 5 * 60 * 1000L

    private const val LOCAL_REAL_MAX_JUMP_SPEED_MPS = 45.0
    private const val LOCAL_MOCK_MAX_JUMP_SPEED_MPS = 10_000.0

    private const val LOCAL_REAL_MAX_BURST_DISTANCE_METERS = 220.0
    private const val LOCAL_REAL_BURST_WINDOW_SECONDS = 8.0
    private const val LOCAL_REAL_OUTLIER_DISTANCE_MULTIPLIER = 1.3
    private const val LOCAL_REAL_ROLLING_DISTANCE_MULTIPLIER = 2.5

    private const val LOCAL_MOCK_MAX_BURST_DISTANCE_METERS = 20_000.0
    private const val LOCAL_MOCK_BURST_WINDOW_SECONDS = 120.0

    private const val WALKING_OVERRIDE_MAX_BURST_DISTANCE_METERS = 140.0
    private const val WALKING_OVERRIDE_BURST_WINDOW_SECONDS = 8.0
    private const val WALKING_OVERRIDE_OUTLIER_DISTANCE_MULTIPLIER = 1.15
    private const val WALKING_OVERRIDE_ROLLING_DISTANCE_MULTIPLIER = 2.0

    const val LOCAL_STALL_REJECT_STREAK_THRESHOLD = 6L
    const val LOCAL_STALL_REANCHOR_MIN_ANCHOR_AGE_MS = 3 * 60 * 1000L

    private const val FALLBACK_MAX_JUMP_SPEED_MPS = 60.0
    private const val FALLBACK_MAX_BURST_DISTANCE_METERS = 300.0
    private const val FALLBACK_BURST_WINDOW_SECONDS = 10.0
    private const val FALLBACK_FRESHNESS_TTL_MS = 2 * 60 * 1000L

    fun ingestConfig(
        maxAccuracyMeters: Float,
        motionMode: TrackingMotionMode,
        isMockLocation: Boolean
    ): TrackPointPolicyConfig {
        return TrackPointPolicyConfig(
            maxAccuracyMeters = maxAccuracyMeters,
            degradedAccuracyMultiplier = 1f,
            allowDegradedAccuracy = false,
            requireAccuracyForAcceptance = true,
            maxFutureSkewMs = MAX_FUTURE_SKEW_MS,
            maxJumpSpeedMps = if (isMockLocation) LOCAL_MOCK_MAX_JUMP_SPEED_MPS else LOCAL_REAL_MAX_JUMP_SPEED_MPS,
            maxBurstDistanceMeters = resolveBurstDistanceMeters(
                motionMode = motionMode,
                isMockLocation = isMockLocation
            ),
            burstWindowSeconds = resolveBurstWindowSeconds(
                motionMode = motionMode,
                isMockLocation = isMockLocation
            ),
            rollingWindowSize = 5,
            outlierDistanceMultiplier = resolveOutlierDistanceMultiplier(
                motionMode = motionMode,
                isMockLocation = isMockLocation
            ),
            rollingDistanceMultiplier = resolveRollingDistanceMultiplier(
                motionMode = motionMode,
                isMockLocation = isMockLocation
            ),
            outlierPolicy = if (isMockLocation) TrackPointOutlierPolicy.OFF else TrackPointOutlierPolicy.ADJUST,
            freshnessTtlMs = LOCAL_FRESHNESS_TTL_MS,
            normalizeSecondsTimestamps = false
        )
    }

    fun fallbackTransitionConfig(): TrackPointPolicyConfig {
        return TrackPointPolicyConfig(
            maxAccuracyMeters = null,
            allowDegradedAccuracy = true,
            requireAccuracyForAcceptance = false,
            maxFutureSkewMs = MAX_FUTURE_SKEW_MS,
            maxJumpSpeedMps = FALLBACK_MAX_JUMP_SPEED_MPS,
            maxBurstDistanceMeters = FALLBACK_MAX_BURST_DISTANCE_METERS,
            burstWindowSeconds = FALLBACK_BURST_WINDOW_SECONDS,
            rollingWindowSize = 5,
            outlierPolicy = TrackPointOutlierPolicy.STRICT,
            freshnessTtlMs = FALLBACK_FRESHNESS_TTL_MS,
            normalizeSecondsTimestamps = false
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

    private fun resolveBurstDistanceMeters(motionMode: TrackingMotionMode, isMockLocation: Boolean): Double {
        if (isMockLocation) return LOCAL_MOCK_MAX_BURST_DISTANCE_METERS
        return if (motionMode == TrackingMotionMode.WALKING) {
            WALKING_OVERRIDE_MAX_BURST_DISTANCE_METERS
        } else {
            LOCAL_REAL_MAX_BURST_DISTANCE_METERS
        }
    }

    private fun resolveBurstWindowSeconds(motionMode: TrackingMotionMode, isMockLocation: Boolean): Double {
        if (isMockLocation) return LOCAL_MOCK_BURST_WINDOW_SECONDS
        return if (motionMode == TrackingMotionMode.WALKING) {
            WALKING_OVERRIDE_BURST_WINDOW_SECONDS
        } else {
            LOCAL_REAL_BURST_WINDOW_SECONDS
        }
    }

    private fun resolveOutlierDistanceMultiplier(motionMode: TrackingMotionMode, isMockLocation: Boolean): Double {
        if (isMockLocation) return 1.5
        return if (motionMode == TrackingMotionMode.WALKING) {
            WALKING_OVERRIDE_OUTLIER_DISTANCE_MULTIPLIER
        } else {
            LOCAL_REAL_OUTLIER_DISTANCE_MULTIPLIER
        }
    }

    private fun resolveRollingDistanceMultiplier(motionMode: TrackingMotionMode, isMockLocation: Boolean): Double {
        if (isMockLocation) return 3.0
        return if (motionMode == TrackingMotionMode.WALKING) {
            WALKING_OVERRIDE_ROLLING_DISTANCE_MULTIPLIER
        } else {
            LOCAL_REAL_ROLLING_DISTANCE_MULTIPLIER
        }
    }
}
