package com.geovault.tracker.pipeline

object TrackPointPolicyCoercion {
    private const val MIN_MAX_JUMP_SPEED_MPS = 1.0
    private const val MAX_MAX_JUMP_SPEED_MPS = 200.0
    private const val MIN_MAX_BURST_DISTANCE_METERS = 5.0
    private const val MAX_MAX_BURST_DISTANCE_METERS = 2_000.0
    private const val MIN_BURST_WINDOW_SECONDS = 0.2
    private const val MAX_BURST_WINDOW_SECONDS = 120.0
    private const val MIN_ROLLING_WINDOW_SIZE = 3
    private const val MAX_ROLLING_WINDOW_SIZE = 20
    private const val MIN_OUTLIER_DISTANCE_MULTIPLIER = 1.0
    private const val MAX_OUTLIER_DISTANCE_MULTIPLIER = 10.0
    private const val MIN_ACCURACY_ENVELOPE_PADDING_METERS = 0.0
    private const val MAX_ACCURACY_ENVELOPE_PADDING_METERS = 500.0
    private const val MIN_ACCURACY_ENVELOPE_MULTIPLIER = 1.0
    private const val MAX_ACCURACY_ENVELOPE_MULTIPLIER = 10.0
    private const val MIN_KINEMATIC_CAP_METERS = 1.0
    private const val MAX_KINEMATIC_CAP_METERS = 2_000.0
    private const val MIN_ROLLING_DISTANCE_MULTIPLIER = 1.0
    private const val MAX_ROLLING_DISTANCE_MULTIPLIER = 10.0

    fun sanitize(config: TrackPointPolicyConfig): TrackPointPolicyConfig {
        val coercedMaxAccuracy = config.maxAccuracyMeters?.coerceIn(1f, 10_000f)
        val coercedFutureSkewMs = config.maxFutureSkewMs.coerceIn(0L, 24L * 60L * 60L * 1000L)
        val coercedFreshnessTtlMs = config.freshnessTtlMs?.coerceIn(0L, 24L * 60L * 60L * 1000L)
        return config.copy(
            maxAccuracyMeters = coercedMaxAccuracy,
            degradedAccuracyMultiplier = config.degradedAccuracyMultiplier.coerceIn(1f, 10f),
            maxFutureSkewMs = coercedFutureSkewMs,
            maxJumpSpeedMps = config.maxJumpSpeedMps?.coerceIn(
                MIN_MAX_JUMP_SPEED_MPS,
                MAX_MAX_JUMP_SPEED_MPS
            ),
            maxBurstDistanceMeters = config.maxBurstDistanceMeters.coerceIn(
                MIN_MAX_BURST_DISTANCE_METERS,
                MAX_MAX_BURST_DISTANCE_METERS
            ),
            burstWindowSeconds = config.burstWindowSeconds.coerceIn(
                MIN_BURST_WINDOW_SECONDS,
                MAX_BURST_WINDOW_SECONDS
            ),
            rollingWindowSize = config.rollingWindowSize.coerceIn(
                MIN_ROLLING_WINDOW_SIZE,
                MAX_ROLLING_WINDOW_SIZE
            ),
            outlierDistanceMultiplier = config.outlierDistanceMultiplier.coerceIn(
                MIN_OUTLIER_DISTANCE_MULTIPLIER,
                MAX_OUTLIER_DISTANCE_MULTIPLIER
            ),
            accuracyEnvelopePaddingMeters = config.accuracyEnvelopePaddingMeters.coerceIn(
                MIN_ACCURACY_ENVELOPE_PADDING_METERS,
                MAX_ACCURACY_ENVELOPE_PADDING_METERS
            ),
            accuracyEnvelopeMultiplier = config.accuracyEnvelopeMultiplier.coerceIn(
                MIN_ACCURACY_ENVELOPE_MULTIPLIER,
                MAX_ACCURACY_ENVELOPE_MULTIPLIER
            ),
            minimumKinematicCapMeters = config.minimumKinematicCapMeters.coerceIn(
                MIN_KINEMATIC_CAP_METERS,
                MAX_KINEMATIC_CAP_METERS
            ),
            rollingDistanceMultiplier = config.rollingDistanceMultiplier.coerceIn(
                MIN_ROLLING_DISTANCE_MULTIPLIER,
                MAX_ROLLING_DISTANCE_MULTIPLIER
            ),
            freshnessTtlMs = coercedFreshnessTtlMs
        )
    }
}
