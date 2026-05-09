package com.geovault.tracker.policy.filter

/**
 * Per-fix derived metrics produced by [LocationMetricsEngine].
 *
 * Distance / speed:
 *  - [rawDistanceMeters] haversine distance from the previous accepted fix
 *  - [effectiveDistanceMeters] raw distance with the combined positional
 *    uncertainty subtracted via Root-Sum-Square (RSS):
 *        max(0, raw - sqrt(prevAcc^2 + currAcc^2))
 *    This is the only distance the filter feeds into speed/burst checks.
 *  - [impliedSpeedMps] effective distance divided by [dtSeconds]
 *  - [reportedSpeedMps] chipset-reported ground speed (mirrors input)
 *  - [reportedBearingDegrees] chipset-reported course over ground
 *
 * Time:
 *  - [dtSeconds] preferred elapsed-realtime delta, else wall-clock delta,
 *    coerced to be non-negative and at least 0.05 s to avoid div-by-zero
 *
 * Uncertainty caps (all in meters; the larger of these wins):
 *  - [accCap] = max(prevAcc, currAcc) * 3
 *  - [kinCap] = max(prevReportedSpeed, currReportedSpeed, 0) * 2 * dt
 *  - [rollingCap] = rollingAvgStep * 3
 *  - [capCandidate] = max(5, accCap, kinCap, rollingCap)
 *  - [accumulatedAccuracySquared] running sum of accuracy^2 across the
 *    streaming session, used by the anchor-trust gate to demote anchors
 *    with growing variance
 *
 * Stability / motion:
 *  - [jerk] |dSpeed|/dt; large values indicate sudden velocity discontinuities
 *  - [deltaHeadingDegrees] absolute shortest-arc bearing change since the
 *    previous fix (0..180)
 *  - [headingChangeRateDegPerSec] [deltaHeadingDegrees] / [dtSeconds]
 *  - [headingQuality] 0..1, blends bearing stability and horizontal accuracy
 *  - [bearingStability] 0..1 over the rolling window (1 == bearings agree)
 *  - [speedStability] 0..1 over the rolling window (1 == speeds agree)
 *  - [impliedAnomaly] 0..1 anomaly score from implied-speed and burst spikes;
 *    inflates the cap and feeds the policy switch's reject branch
 *  - [stationary] multi-signal stationary classification; see
 *    [StationaryConfidence] for the score, isStationary flag, and the
 *    rubber-banding (oscillation) flag
 *  - [anchorTrust] 0..1 confidence in the previous accepted anchor; 0 means
 *    "do not trust prior anchor for cap inflation"
 */
data class LocationMetrics(
    val rawDistanceMeters: Double,
    val effectiveDistanceMeters: Double,
    val dtSeconds: Double,
    val impliedSpeedMps: Double,
    val reportedSpeedMps: Double,
    val reportedBearingDegrees: Double,
    val accCap: Double,
    val kinCap: Double,
    val rollingCap: Double,
    val capCandidate: Double,
    val accumulatedAccuracySquared: Double,
    val jerk: Double,
    val deltaHeadingDegrees: Double,
    val headingChangeRateDegPerSec: Double,
    val headingQuality: Double,
    val bearingStability: Double,
    val speedStability: Double,
    val impliedAnomaly: Double,
    val stationary: StationaryConfidence,
    val anchorTrust: Double,
    val accuracyMeters: Double,
    val previousAccuracyMeters: Double,
    val rollingAverageStepMeters: Double,
    val burstDistanceMeters: Double,
)
