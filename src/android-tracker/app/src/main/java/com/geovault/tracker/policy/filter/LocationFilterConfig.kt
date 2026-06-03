package com.geovault.tracker.policy.filter

private const val GENERIC_MAX_IMPLIED_SPEED_MPS = 60.0
private const val GENERIC_MAX_BURST_DISTANCE_METERS = 300.0
private const val GENERIC_BURST_WINDOW_SECONDS = 10.0
private const val GENERIC_ROLLING_WINDOW_SECONDS = 5.0

private val GenericMovementCandidateConfig = MovementCandidateConfig(
    enabled = true,
    suspectDistanceMeters = 300.0,
    suspectAccuracyMeters = 60.0,
    suspectImpliedSpeedMps = 35.0,
    consistencyMeters = 100.0,
    confirmationWindowMs = 15_000L,
    requiredConsistentFixes = 1,
    requiredPromotableFixes = 1,
    promotionAccuracyMeters = 80.0,
)

/**
 * Configuration for the per-stream [LocationFilter] pipeline plus the
 * surviving non-filter gates that the [com.geovault.tracker.policy.TrackPointPolicyEngine]
 * facade still owns (timestamp normalisation, freshness TTL, future-skew).
 *
 * Defaults are motion-preset independent: the same generic gates run for
 * walking, biking, and driving sessions. Speed-selected presets only change
 * numeric thresholds and request cadence.
 *
 * Numeric ranges:
 *
 * @property useKalman feed each accepted fix through the adaptive 1D Kalman
 *   pass before the policy switch. Disable for diagnostics only.
 * @property kalmanProfile preset for [KalmanTuning].
 * @property policy outlier handling strategy. See [LocationFilterPolicy].
 * @property maxImpliedSpeedMps absolute upper bound on `raw / dt` between
 *   two consecutive seen fixes (m/s). This is intentionally supplied by
 *   the active motion preset; on-foot tracking must not inherit highway
 *   headroom.
 * @property maxBurstDistanceMeters raw-distance threshold for the burst
 *   term of the implied-anomaly check. A fix with `raw > maxBurst` AND
 *   `dt <= burstWindow` is flagged as anomalous.
 * @property burstWindowSeconds dt ceiling for the burst term. Above this
 *   `dt`, a large `raw` is just a legitimate sparse-fix highway hop and
 *   does not contribute to the anomaly.
 * @property trackingAccuracyThresholdMeters reported accuracy beyond which
 *   we reject the raw fix without scoring it. 100 m matches the user-visible
 *   "tracking accuracy" preference and is generally larger than legitimate
 *   open-sky variance.
 * @property rollingWindowSeconds window over which the metrics engine
 *   tracks the rolling average step distance, used to derive the third
 *   uncertainty cap (`rollingCap`). Anything below 3 s makes the cap noisy.
 * @property speedRecovery preset-tuned confirmation for sustained motion
 *   above the nominal speed cap. This is generic recovery evidence, not a
 *   route or activity special case.
 * @property staleAnchorMinAgeMs anchor age after which a far relocation
 *   requires continuity confirmation before replacing the anchor.
 * @property staleAnchorMinDistanceMeters minimum anchor-to-fix displacement
 *   for stale-relocation confirmation. Smaller moves continue through the
 *   ordinary policy to preserve normal sparse driving.
 * @property resumeConfirmationMinDistanceMeters post-pause raw displacement
 *   that must be confirmed by a second consistent fix before becoming the
 *   new anchor.
 * @property resumeConfirmationConsistencyMeters maximum distance between
 *   the held resume candidate and a follow-up fix for confirmation.
 * @property resumeConfirmationMaxAccuracyMeters maximum reported accuracy
 *   for fixes that can participate in substantial resume confirmation.
 * @property resumeConfirmationWindowMs candidate lifetime. A late follow-up
 *   starts a new candidate instead of promoting stale evidence.
 * @property maxFutureSkewMs reject fixes whose normalized event time is
 *   more than this far in the future (clock skew protection).
 * @property freshnessTtlMs reject fixes that are older than this at ingest
 *   (stale TTL).
 * @property normalizeSecondsTimestamps treat numeric timestamps that look
 *   like seconds as seconds and rescale to ms.
 */
data class LocationFilterConfig(
    val useKalman: Boolean = true,
    val kalmanProfile: KalmanProfile = KalmanProfile.Default,
    val policy: LocationFilterPolicy = LocationFilterPolicy.Conservative,
    val maxImpliedSpeedMps: Double = GENERIC_MAX_IMPLIED_SPEED_MPS,
    val maxBurstDistanceMeters: Double = GENERIC_MAX_BURST_DISTANCE_METERS,
    val burstWindowSeconds: Double = GENERIC_BURST_WINDOW_SECONDS,
    val trackingAccuracyThresholdMeters: Double = 100.0,
    val rollingWindowSeconds: Double = GENERIC_ROLLING_WINDOW_SECONDS,
    val kinematicCap: KinematicCapConfig = KinematicCapConfig.Default,
    val movementCandidate: MovementCandidateConfig = GenericMovementCandidateConfig,
    val speedRecovery: SpeedRecoveryConfig = SpeedRecoveryConfig.Disabled,
    val anchorHealth: AnchorHealthConfig = AnchorHealthConfig.Default,
    val staleAnchorMinAgeMs: Long = 2L * 60L * 1000L,
    val staleAnchorMinDistanceMeters: Double = 600.0,
    val resumeConfirmationMinDistanceMeters: Double = 150.0,
    val resumeConfirmationConsistencyMeters: Double = 75.0,
    val resumeConfirmationMaxAccuracyMeters: Double = 50.0,
    val resumeConfirmationWindowMs: Long = 20_000L,
    val maxFutureSkewMs: Long = 60_000L,
    val freshnessTtlMs: Long = 30_000L,
    val normalizeSecondsTimestamps: Boolean = true,
) {
    /**
     * True when transitioning from `this` config to [other] requires the
     * [LocationFilter] to rebuild internal physics state (kalman, metrics
     * engine ring buffer). The committed anchor is preserved by
     * [LocationFilter.applyConfig]; explicit stream resets are responsible
     * for clearing anchors.
     *
     * Only fields that change the filter's *physics* qualify. Pure per-fix
     * gates ([trackingAccuracyThresholdMeters], [maxFutureSkewMs],
     * [freshnessTtlMs], [normalizeSecondsTimestamps]) are evaluated
     * statelessly on every call to [LocationFilter.evaluate] and can be
     * live-swapped without disturbing the anchor.
     *
     * This guarantee is what keeps the filter stable across user setting
     * changes and any future preset threshold tweaks: a benign config
     * mutation must never produce a `first-fix` accept of the next noisy
     * sample.
     */
    fun requiresFilterStateReset(other: LocationFilterConfig): Boolean {
        return policy != other.policy ||
            useKalman != other.useKalman ||
            kalmanProfile != other.kalmanProfile ||
            rollingWindowSeconds != other.rollingWindowSeconds ||
            maxImpliedSpeedMps != other.maxImpliedSpeedMps ||
            maxBurstDistanceMeters != other.maxBurstDistanceMeters ||
            burstWindowSeconds != other.burstWindowSeconds ||
            kinematicCap != other.kinematicCap ||
            movementCandidate != other.movementCandidate ||
            speedRecovery != other.speedRecovery ||
            anchorHealth != other.anchorHealth ||
            staleAnchorMinAgeMs != other.staleAnchorMinAgeMs ||
            staleAnchorMinDistanceMeters != other.staleAnchorMinDistanceMeters ||
            resumeConfirmationMinDistanceMeters != other.resumeConfirmationMinDistanceMeters ||
            resumeConfirmationConsistencyMeters != other.resumeConfirmationConsistencyMeters ||
            resumeConfirmationMaxAccuracyMeters != other.resumeConfirmationMaxAccuracyMeters ||
            resumeConfirmationWindowMs != other.resumeConfirmationWindowMs
    }

    companion object {
        val Default: LocationFilterConfig = LocationFilterConfig()

        fun fromTuning(
            tuning: MotionProfileTuning,
            trackingAccuracyThresholdMeters: Double,
            maxFutureSkewMs: Long,
            freshnessTtlMs: Long,
            normalizeSecondsTimestamps: Boolean,
        ): LocationFilterConfig = LocationFilterConfig(
            maxImpliedSpeedMps = tuning.maxImpliedSpeedMps,
            maxBurstDistanceMeters = tuning.maxBurstDistanceMeters,
            burstWindowSeconds = tuning.burstWindowSeconds,
            trackingAccuracyThresholdMeters = trackingAccuracyThresholdMeters,
            rollingWindowSeconds = tuning.rollingWindowSeconds,
            kinematicCap = tuning.kinematicCap,
            movementCandidate = tuning.movementCandidate,
            speedRecovery = tuning.speedRecovery,
            anchorHealth = tuning.anchorHealth,
            maxFutureSkewMs = maxFutureSkewMs,
            freshnessTtlMs = freshnessTtlMs,
            normalizeSecondsTimestamps = normalizeSecondsTimestamps,
        )
    }
}
