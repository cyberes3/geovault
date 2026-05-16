package com.geovault.tracker.policy.filter

import com.geovault.common.geo.GeoMath
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Stateful metrics engine that produces a [LocationMetrics] snapshot for
 * each fix. Owns a 20-slot ring buffer of recent observations to compute
 * rolling average step and bearing/speed stability.
 *
 * The engine is intentionally allocation-light: the ring buffer is a fixed
 * Array of [Sample]s reused across calls, and every per-fix derivation is
 * a pure scalar.
 *
 * Math (mirrors the contract in [LocationMetrics]):
 *  - Effective distance uses Root-Sum-Square accuracy combination, which
 *    is the only mathematically defensible way to merge two independent
 *    Gaussian uncertainty estimates.
 *  - The accuracy cap is `max(prevAcc, currAcc) * 3`. This is intentionally
 *    not the *sum* of accuracies: combining two 65 m envelopes via a sum
 *    yields a 390 m allowance, which is exactly the over-permissive band
 *    that produces rubber-banding at slow walking speed.
 *  - The kinematic cap is driven by GPS-reported speed (either `prevSpeed`
 *    or `currSpeed`) when present. Some mock / fused providers omit speed;
 *    for high-confidence fixes only, implied speed is allowed to stand in.
 *    A noisy standstill fix with poor accuracy still contributes ~0 m.
 *  - The rolling cap is the rolling step distance averaged over the
 *    configured window, scaled by 3.
 *
 * Stationary classification is delegated to
 * [StationaryConfidenceCalculator]; this engine's responsibility is to
 * keep the rolling buffer and compute the per-fix scalars the calculator
 * consumes (stability, jerk, raw vs accuracy ratio).
 */
class LocationMetricsEngine(
    private val rollingWindowSeconds: Double = 5.0,
    private val burstWindowSeconds: Double = 10.0,
    private val maxImpliedSpeedMps: Double = 60.0,
    private val maxBurstDistanceMeters: Double = 300.0,
    private val kinematicCapPolicy: KinematicCapPolicy = KinematicCapPolicy(KinematicCapConfig.Default),
) {
    private data class Sample(
        val timestampMs: Long,
        /**
         * Distance along the *committed* polyline from the previously
         * committed sample to this sample. Raw haversine of suppressed
         * jitter is intentionally not stored here, so a noisy standstill
         * does not poison the rolling-step window.
         */
        val committedDisplacementMeters: Double,
        val reportedSpeedMps: Double,
        val reportedBearingDegrees: Double,
    )

    private val ring = ArrayDeque<Sample>(RING_CAPACITY)
    private var accumulatedAccuracySquared: Double = 0.0
    private var lastReportedSpeedMps: Double = 0.0
    private var lastBearingDegrees: Double = Double.NaN
    private var anchorTrust: Double = 1.0

    fun reset() {
        ring.clear()
        accumulatedAccuracySquared = 0.0
        lastReportedSpeedMps = 0.0
        lastBearingDegrees = Double.NaN
        anchorTrust = 1.0
    }

    /**
     * Pure metrics derivation for the candidate fix. No internal state is
     * mutated. Callers must invoke [commit] *only* once they have decided
     * to accept or adjust the fix; rejected fixes never enter the ring
     * buffer or running anchor accumulators.
     *
     * @param current new observation
     * @param previous reference fix used for `dt`, `raw`, and the
     *   anomaly/cap calculations. Callers should pass the *last seen*
     *   fix (accepted, adjusted, or rejected after passing the
     *   accuracy gate) so that consecutive rejects can't unbound
     *   `dt`/`raw`. Null on the first fix in a session or after a
     *   reset.
     */
    fun compute(current: LocationInput, previous: LocationInput?): LocationMetrics {
        val accuracy = (current.accuracyMeters?.toDouble() ?: DEFAULT_ACCURACY_FALLBACK_METERS)
            .coerceAtLeast(0.0)
        val previousAccuracy = (previous?.accuracyMeters?.toDouble() ?: 0.0).coerceAtLeast(0.0)

        val rawDistance = if (previous == null) {
            0.0
        } else {
            GeoMath.haversineMeters(previous.latitude, previous.longitude, current.latitude, current.longitude)
        }

        val rssAccuracy = sqrt(previousAccuracy * previousAccuracy + accuracy * accuracy)
        val effectiveDistance = max(0.0, rawDistance - rssAccuracy)

        val dtSeconds = computeDtSeconds(previous, current)
        val impliedSpeed = if (dtSeconds > 0.0) effectiveDistance / dtSeconds else 0.0

        val currentReportedSpeed = (current.speedMps?.toDouble() ?: Double.NaN).let { v ->
            if (v.isNaN() || v < 0.0) Double.NaN else v
        }
        val previousReportedSpeed = (previous?.speedMps?.toDouble() ?: Double.NaN).let { v ->
            if (v.isNaN() || v < 0.0) Double.NaN else v
        }

        val effectiveCurrentSpeed = if (currentReportedSpeed.isNaN()) 0.0 else currentReportedSpeed
        val effectivePreviousSpeed = if (previousReportedSpeed.isNaN()) 0.0 else previousReportedSpeed

        val jerk = computeJerk(current = effectiveCurrentSpeed, previous = lastReportedSpeedMps, dtSeconds = dtSeconds)

        val (bearingStability, speedStability) = computeStability()
        val accCap = max(previousAccuracy, accuracy) * 3.0
        val speedForKinematicCap = kinematicCapPolicy.resolve(
            reportedSpeedMps = max(effectivePreviousSpeed, effectiveCurrentSpeed),
            impliedSpeedMps = impliedSpeed,
            maxAccuracyMeters = max(previousAccuracy, accuracy),
            dtSeconds = dtSeconds,
            speedStability = speedStability,
            bearingStability = bearingStability,
        ).trustedSpeedMps
        val kinCap = if (dtSeconds > 0.0) speedForKinematicCap * 2.0 * dtSeconds else 0.0

        val rollingAvgStep = computeRollingAverageStepMeters()
        val rollingCap = rollingAvgStep * 3.0

        val capCandidate = maxOf(MIN_CAP_FLOOR_METERS, accCap, kinCap, rollingCap)

        val headingQuality = computeHeadingQuality(
            bearingStability = bearingStability,
            accuracyMeters = accuracy,
        )

        val impliedAnomaly = computeImpliedAnomaly(
            rawDistance = rawDistance,
            dtSeconds = dtSeconds,
        )

        val stationary = StationaryConfidenceCalculator.evaluate(
            StationaryConfidenceCalculator.Input(
                reportedSpeedMps = effectiveCurrentSpeed,
                bearingStability = bearingStability,
                speedStability = speedStability,
                jerk = jerk,
                accuracyMeters = accuracy,
                rawDistanceMeters = rawDistance,
                effectiveDistanceMeters = effectiveDistance,
                bufferCount = ring.size,
            )
        )

        val nextAccumulatedAccuracySq = accumulatedAccuracySquared + (accuracy * accuracy)
        val nextAnchorTrust = computeAnchorTrust(
            previousTrust = anchorTrust,
            currentAccuracy = accuracy,
            stationaryConfidence = stationary.score,
            headingQuality = headingQuality,
            accumulatedAccuracySquared = nextAccumulatedAccuracySq,
        )

        return LocationMetrics(
            rawDistanceMeters = rawDistance,
            effectiveDistanceMeters = effectiveDistance,
            dtSeconds = dtSeconds,
            impliedSpeedMps = impliedSpeed,
            reportedSpeedMps = if (currentReportedSpeed.isNaN()) 0.0 else currentReportedSpeed,
            reportedBearingDegrees = current.bearingDegrees?.toDouble() ?: Double.NaN,
            accCap = accCap,
            kinCap = kinCap,
            rollingCap = rollingCap,
            capCandidate = capCandidate,
            accumulatedAccuracySquared = nextAccumulatedAccuracySq,
            jerk = jerk,
            headingQuality = headingQuality,
            bearingStability = bearingStability,
            speedStability = speedStability,
            impliedAnomaly = impliedAnomaly,
            stationary = stationary,
            anchorTrust = nextAnchorTrust,
            accuracyMeters = accuracy,
            previousAccuracyMeters = previousAccuracy,
            rollingAverageStepMeters = rollingAvgStep,
        )
    }

    /**
     * Persist the candidate fix into the ring buffer and update the
     * running anchor accumulators (accumulated accuracy, last reported
     * speed, last bearing, anchor trust). Must be called only when the
     * caller decides to accept or adjust the fix.
     *
     * @param current the candidate input *after* any clip/adjust applied
     *   by the caller -- its lat/lon must reflect what was actually
     *   committed to the trail.
     * @param committedDisplacementMeters the haversine distance from the
     *   previously committed anchor to [current]. Verbatim accept passes
     *   `metrics.rawDistanceMeters`; clip passes the cap; adjust-to-anchor
     *   passes 0.0. This drives the rolling-step window; using committed
     *   (not raw) distance prevents standstill jitter from poisoning the
     *   window that subsequent decisions are made against.
     */
    fun commit(
        current: LocationInput,
        metrics: LocationMetrics,
        committedDisplacementMeters: Double,
    ) {
        recordSample(
            current = current,
            committedDisplacement = committedDisplacementMeters.coerceAtLeast(0.0),
            reportedSpeed = metrics.reportedSpeedMps,
        )
        accumulatedAccuracySquared = metrics.accumulatedAccuracySquared
        lastReportedSpeedMps = metrics.reportedSpeedMps
        lastBearingDegrees = current.bearingDegrees?.toDouble() ?: lastBearingDegrees
        anchorTrust = metrics.anchorTrust
    }

    private fun computeDtSeconds(previous: LocationInput?, current: LocationInput): Double {
        if (previous == null) return 0.0
        val prevElapsed = previous.elapsedRealtimeNanos
        val currElapsed = current.elapsedRealtimeNanos
        if (prevElapsed != null && currElapsed != null && currElapsed >= prevElapsed) {
            val dtNs = (currElapsed - prevElapsed).toDouble() / 1_000_000_000.0
            return max(MIN_DT_SECONDS, dtNs)
        }
        val dtMs = (current.timestampMs - previous.timestampMs).toDouble()
        return max(MIN_DT_SECONDS, dtMs / 1000.0)
    }

    private fun computeRollingAverageStepMeters(): Double {
        if (ring.isEmpty()) return DEFAULT_ROLLING_FALLBACK_METERS
        val cutoffSeconds = rollingWindowSeconds.coerceAtLeast(1.0)
        val now = ring.last().timestampMs
        var total = 0.0
        var count = 0
        for (s in ring) {
            val ageSec = (now - s.timestampMs) / 1000.0
            if (ageSec <= cutoffSeconds) {
                total += s.committedDisplacementMeters
                count++
            }
        }
        return if (count > 0) total / count else DEFAULT_ROLLING_FALLBACK_METERS
    }

    private fun computeJerk(current: Double, previous: Double, dtSeconds: Double): Double {
        if (dtSeconds <= 0.0) return 0.0
        return abs(current - previous) / dtSeconds
    }

    private fun computeStability(): Pair<Double, Double> {
        if (ring.size < 2) return 1.0 to 1.0
        val recent = ring.takeLast(STABILITY_SAMPLES)
        val bearings = recent.mapNotNull { s ->
            val v = s.reportedBearingDegrees
            if (v.isNaN()) null else v
        }
        val bearingStability = if (bearings.size < 2) 1.0 else {
            var totalDelta = 0.0
            for (i in 1 until bearings.size) {
                totalDelta += GeoMath.shortestBearingDeltaDegrees(bearings[i - 1], bearings[i])
            }
            val mean = totalDelta / (bearings.size - 1)
            (1.0 - (mean / 180.0)).coerceIn(0.0, 1.0)
        }
        val speeds = recent.map { it.reportedSpeedMps }
        val speedStability = if (speeds.size < 2) {
            1.0
        } else {
            val avg = speeds.average()
            if (avg <= 0.05) {
                1.0
            } else {
                val variance = speeds.fold(0.0) { acc, v -> acc + (v - avg) * (v - avg) } / speeds.size
                val cv = sqrt(variance) / avg
                (1.0 - min(cv, 1.0)).coerceIn(0.0, 1.0)
            }
        }
        return bearingStability to speedStability
    }

    private fun computeHeadingQuality(bearingStability: Double, accuracyMeters: Double): Double {
        val accuracyFactor = (1.0 - (accuracyMeters / 60.0)).coerceIn(0.0, 1.0)
        return (bearingStability * 0.6 + accuracyFactor * 0.4).coerceIn(0.0, 1.0)
    }

    /**
     * Implied-anomaly check.
     *
     * Two strict gates:
     *  - `raw / dt > maxImpliedSpeed`: per-fix implied speed exceeds the
     *    configured ceiling (e.g. >60 m/s = >216 km/h is not real motion).
     *  - `raw > maxBurstDistance && dt <= burstWindow`: a tightly-clustered
     *    raw displacement burst that is too dense to be physical. Both
     *    conditions are required; a large raw at a *long* dt is just a
     *    legitimate sparse-fix highway hop, not a burst.
     *
     * Note: speed term uses **raw / dt**, not the RSS-corrected
     * `impliedSpeedMps`. Effective distance collapses for high-noise fixes,
     * which would let real teleports slip past the gate.
     */
    private fun computeImpliedAnomaly(
        rawDistance: Double,
        dtSeconds: Double,
    ): Boolean {
        if (dtSeconds <= 0.0) return false
        val rawImpliedSpeed = rawDistance / dtSeconds
        return rawImpliedSpeed > maxImpliedSpeedMps ||
            (rawDistance > maxBurstDistanceMeters && dtSeconds <= burstWindowSeconds)
    }

    private fun computeAnchorTrust(
        previousTrust: Double,
        currentAccuracy: Double,
        stationaryConfidence: Double,
        headingQuality: Double,
        accumulatedAccuracySquared: Double,
    ): Double {
        val accuracyTerm = (1.0 - min(currentAccuracy / 60.0, 1.0)).coerceIn(0.0, 1.0)
        val varianceTerm = (1.0 - min(sqrt(accumulatedAccuracySquared) / 5_000.0, 1.0)).coerceIn(0.0, 1.0)
        val headingTerm = headingQuality
        val rubberBandPenalty = if (stationaryConfidence > 0.6 && currentAccuracy > 35.0) 0.4 else 1.0
        val instantaneous = (accuracyTerm * 0.4 + varianceTerm * 0.3 + headingTerm * 0.3) * rubberBandPenalty
        return (previousTrust * 0.6 + instantaneous * 0.4).coerceIn(0.0, 1.0)
    }

    private fun recordSample(
        current: LocationInput,
        committedDisplacement: Double,
        reportedSpeed: Double,
    ) {
        if (ring.size >= RING_CAPACITY) ring.removeFirst()
        ring.addLast(
            Sample(
                timestampMs = current.timestampMs,
                committedDisplacementMeters = committedDisplacement,
                reportedSpeedMps = reportedSpeed,
                reportedBearingDegrees = current.bearingDegrees?.toDouble() ?: Double.NaN,
            )
        )
    }

    companion object {
        private const val RING_CAPACITY = 20
        private const val STABILITY_SAMPLES = 8
        private const val MIN_DT_SECONDS = 0.05
        private const val MIN_CAP_FLOOR_METERS = 5.0
        private const val DEFAULT_ROLLING_FALLBACK_METERS = 6.0
        private const val DEFAULT_ACCURACY_FALLBACK_METERS = 65.0
    }
}
