package com.geovault.tracker.policy.filter

/**
 * Configuration for the per-stream [LocationFilter] pipeline plus the
 * surviving non-filter gates that the [com.geovault.tracker.policy.TrackPointPolicyEngine]
 * facade still owns (timestamp normalisation, freshness TTL, future-skew).
 *
 * Defaults are profile-independent: the same tuning runs for walking,
 * biking, and driving sessions. Speed profiles only change the
 * [com.geovault.tracker.policy.TrackingPolicyProfiles] LocationRequest
 * (interval / distance-filter) and the user-facing accuracy threshold.
 *
 * Numeric ranges:
 *
 * @property useKalman feed each accepted fix through the adaptive 1D Kalman
 *   pass before the policy switch. Disable for diagnostics only.
 * @property kalmanProfile preset for [KalmanTuning].
 * @property policy outlier handling strategy. See [LocationFilterPolicy].
 * @property maxImpliedSpeedMps absolute upper bound on `raw / dt` between
 *   two consecutive seen fixes (m/s). Default 60 m/s (216 km/h) gives
 *   comfortable highway headroom while still catching teleports.
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
    val maxImpliedSpeedMps: Double = 60.0,
    val maxBurstDistanceMeters: Double = 300.0,
    val burstWindowSeconds: Double = 10.0,
    val trackingAccuracyThresholdMeters: Double = 100.0,
    val rollingWindowSeconds: Double = 5.0,
    val maxFutureSkewMs: Long = 60_000L,
    val freshnessTtlMs: Long = 30_000L,
    val normalizeSecondsTimestamps: Boolean = true,
) {
    companion object {
        val Default: LocationFilterConfig = LocationFilterConfig()
    }
}
