package com.geovault.tracker.policy.filter

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Stateful metrics engine that produces a [LocationMetrics] snapshot for
 * each fix. Owns a 20-slot ring buffer of recent observations to compute
 * rolling average step, bearing/speed stability, and burst-distance.
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
 * The stationary detector blends multiple signals:
 *  - bearingStability and speedStability over the ring buffer
 *  - reported speed magnitude
 *  - accuracy-vs-displacement ratio (oscillating fixes have small
 *    displacement and large accuracy)
 *  - heading-quality (bearing usable / accuracy small)
 *  - jerk (low jerk == steady walk; high jerk + low displacement is the
 *    classic rubber-banding pattern)
 *
 * The output [LocationMetrics.stationaryConfidence] is a 0..1 weighted
 * sum; [LocationMetrics.isStationary] is true iff confidence >= 0.55, and
 * [LocationMetrics.isOscillating] is true iff stationary AND accuracy is
 * "moving" (delta accuracy + heading change rate are above thresholds)
 * while raw distance is non-trivial -- the textbook rubber-banding
 * signature.
 */
class LocationMetricsEngine(
    private val rollingWindowSeconds: Double = 5.0,
    private val burstWindowSeconds: Double = 2.0,
) {
    private data class Sample(
        val timestampMs: Long,
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Double,
        /**
         * Distance along the *committed* polyline from the previously
         * committed sample to this sample. Raw haversine of suppressed
         * jitter is intentionally not stored here, so a noisy standstill
         * does not poison the rolling-step or burst windows.
         */
        val committedDisplacementMeters: Double,
        val effectiveDistanceMeters: Double,
        val dtSeconds: Double,
        val impliedSpeedMps: Double,
        val reportedSpeedMps: Double,
        val reportedBearingDegrees: Double,
    )

    private val ring = ArrayDeque<Sample>(RING_CAPACITY)
    private var accumulatedAccuracySquared: Double = 0.0
    private var lastReportedSpeedMps: Double = 0.0
    private var lastImpliedSpeedMps: Double = 0.0
    private var lastBearingDegrees: Double = Double.NaN
    private var anchorTrust: Double = 1.0

    fun reset() {
        ring.clear()
        accumulatedAccuracySquared = 0.0
        lastReportedSpeedMps = 0.0
        lastImpliedSpeedMps = 0.0
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
     * @param previous previous accepted anchor; null on the first fix in
     *   a session or after a reset
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

        val accCap = max(previousAccuracy, accuracy) * 3.0
        val speedForKinematicCap = resolveSpeedForKinematicCap(
            reportedSpeedMps = max(effectivePreviousSpeed, effectiveCurrentSpeed),
            impliedSpeedMps = impliedSpeed,
            maxAccuracyMeters = max(previousAccuracy, accuracy),
            dtSeconds = dtSeconds,
        )
        val kinCap = if (dtSeconds > 0.0) speedForKinematicCap * 2.0 * dtSeconds else 0.0

        val rollingAvgStep = computeRollingAverageStepMeters()
        val rollingCap = rollingAvgStep * 3.0

        val capCandidate = maxOf(MIN_CAP_FLOOR_METERS, accCap, kinCap, rollingCap)

        val burstDistance = computeBurstDistanceMeters(currentRawDistance = rawDistance, currentDtSeconds = dtSeconds)

        val (deltaHeadingDeg, headingChangeRate) = computeHeadingChange(current, dtSeconds)

        val jerk = computeJerk(current = effectiveCurrentSpeed, previous = lastReportedSpeedMps, dtSeconds = dtSeconds)

        val (bearingStability, speedStability) = computeStability()
        val headingQuality = computeHeadingQuality(
            bearingStability = bearingStability,
            accuracyMeters = accuracy,
        )

        val impliedAnomaly = computeImpliedAnomaly(
            impliedSpeed = impliedSpeed,
            reportedSpeed = if (currentReportedSpeed.isNaN()) impliedSpeed else currentReportedSpeed,
            burstDistance = burstDistance,
        )

        val stationaryConfidence = computeStationaryConfidence(
            speed = effectiveCurrentSpeed,
            impliedSpeed = impliedSpeed,
            bearingStability = bearingStability,
            speedStability = speedStability,
            jerk = jerk,
            accuracyMeters = accuracy,
            rawDistance = rawDistance,
        )
        val isStationary = stationaryConfidence >= STATIONARY_THRESHOLD
        val isOscillating = isStationary &&
            rawDistance > 1.0 &&
            (headingChangeRate > OSCILLATION_HEADING_RATE_DEG_PER_SEC || jerk > OSCILLATION_JERK_THRESHOLD)

        val nextAccumulatedAccuracySq = accumulatedAccuracySquared + (accuracy * accuracy)
        val nextAnchorTrust = computeAnchorTrust(
            previousTrust = anchorTrust,
            currentAccuracy = accuracy,
            stationaryConfidence = stationaryConfidence,
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
            deltaHeadingDegrees = deltaHeadingDeg,
            headingChangeRateDegPerSec = headingChangeRate,
            headingQuality = headingQuality,
            bearingStability = bearingStability,
            speedStability = speedStability,
            impliedAnomaly = impliedAnomaly,
            isStationary = isStationary,
            isOscillating = isOscillating,
            stationaryConfidence = stationaryConfidence,
            anchorTrust = nextAnchorTrust,
            accuracyMeters = accuracy,
            previousAccuracyMeters = previousAccuracy,
            rollingAverageStepMeters = rollingAvgStep,
            burstDistanceMeters = burstDistance,
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
     *   passes 0.0. This drives rolling-step and burst windows; using
     *   committed (not raw) distance prevents standstill jitter from
     *   poisoning the windows that subsequent decisions are made against.
     */
    fun commit(
        current: LocationInput,
        metrics: LocationMetrics,
        committedDisplacementMeters: Double,
    ) {
        val effectiveCurrentSpeed = metrics.reportedSpeedMps
        recordSample(
            current = current,
            accuracy = metrics.accuracyMeters,
            committedDisplacement = committedDisplacementMeters.coerceAtLeast(0.0),
            effectiveDistance = metrics.effectiveDistanceMeters,
            dtSeconds = metrics.dtSeconds,
            impliedSpeed = metrics.impliedSpeedMps,
            reportedSpeed = effectiveCurrentSpeed,
        )
        accumulatedAccuracySquared = metrics.accumulatedAccuracySquared
        lastReportedSpeedMps = effectiveCurrentSpeed
        lastImpliedSpeedMps = metrics.impliedSpeedMps
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

    private fun computeBurstDistanceMeters(currentRawDistance: Double, currentDtSeconds: Double): Double {
        if (ring.isEmpty()) return currentRawDistance
        val window = burstWindowSeconds.coerceAtLeast(0.2)
        val newestTs = ring.last().timestampMs
        var sum = currentRawDistance
        var elapsed = currentDtSeconds
        for (i in ring.indices.reversed()) {
            if (elapsed > window) break
            val s = ring[i]
            sum += s.committedDisplacementMeters
            elapsed += (newestTs - s.timestampMs) / 1000.0
        }
        return sum
    }

    private fun resolveSpeedForKinematicCap(
        reportedSpeedMps: Double,
        impliedSpeedMps: Double,
        maxAccuracyMeters: Double,
        dtSeconds: Double,
    ): Double {
        val safeReported = reportedSpeedMps.coerceAtLeast(0.0)
        val canTrustImplied = dtSeconds >= IMPLIED_SPEED_FALLBACK_MIN_DT_SECONDS &&
            impliedSpeedMps >= IMPLIED_SPEED_FALLBACK_MIN_SPEED_MPS &&
            maxAccuracyMeters <= IMPLIED_SPEED_FALLBACK_MAX_ACCURACY_METERS
        return if (canTrustImplied) max(safeReported, impliedSpeedMps) else safeReported
    }

    private fun computeHeadingChange(current: LocationInput, dtSeconds: Double): Pair<Double, Double> {
        val currentBearing = current.bearingDegrees?.toDouble()
        if (currentBearing == null || lastBearingDegrees.isNaN()) {
            return 0.0 to 0.0
        }
        val delta = GeoMath.shortestBearingDeltaDegrees(lastBearingDegrees, currentBearing)
        val rate = if (dtSeconds > 0.0) delta / dtSeconds else 0.0
        return delta to rate
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

    private fun computeImpliedAnomaly(
        impliedSpeed: Double,
        reportedSpeed: Double,
        burstDistance: Double,
    ): Double {
        val speedReference = max(reportedSpeed, 0.5)
        val speedRatio = (impliedSpeed / speedReference).coerceAtLeast(0.0)
        val speedScore = when {
            speedRatio <= 1.5 -> 0.0
            speedRatio >= 4.0 -> 1.0
            else -> (speedRatio - 1.5) / (4.0 - 1.5)
        }
        val burstScore = when {
            burstDistance <= 50.0 -> 0.0
            burstDistance >= 250.0 -> 1.0
            else -> (burstDistance - 50.0) / (250.0 - 50.0)
        }
        return max(speedScore, burstScore).coerceIn(0.0, 1.0)
    }

    private fun computeStationaryConfidence(
        speed: Double,
        impliedSpeed: Double,
        bearingStability: Double,
        speedStability: Double,
        jerk: Double,
        accuracyMeters: Double,
        rawDistance: Double,
    ): Double {
        val speedTerm = (1.0 - min(speed / 1.5, 1.0)).coerceIn(0.0, 1.0)
        val impliedTerm = (1.0 - min(impliedSpeed / 1.5, 1.0)).coerceIn(0.0, 1.0)
        val stabilityTerm = (bearingStability * 0.5 + speedStability * 0.5).coerceIn(0.0, 1.0)
        val jerkTerm = (1.0 - min(jerk / 4.0, 1.0)).coerceIn(0.0, 1.0)
        val accuracyVsDisplacement = if (accuracyMeters <= 0.0 || rawDistance <= 0.0) {
            0.5
        } else {
            (accuracyMeters / max(rawDistance, 1.0)).coerceIn(0.0, 1.0)
        }
        return (speedTerm * 0.30 +
            impliedTerm * 0.25 +
            stabilityTerm * 0.15 +
            jerkTerm * 0.15 +
            accuracyVsDisplacement * 0.15).coerceIn(0.0, 1.0)
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
        accuracy: Double,
        committedDisplacement: Double,
        effectiveDistance: Double,
        dtSeconds: Double,
        impliedSpeed: Double,
        reportedSpeed: Double,
    ) {
        if (ring.size >= RING_CAPACITY) ring.removeFirst()
        ring.addLast(
            Sample(
                timestampMs = current.timestampMs,
                latitude = current.latitude,
                longitude = current.longitude,
                accuracyMeters = accuracy,
                committedDisplacementMeters = committedDisplacement,
                effectiveDistanceMeters = effectiveDistance,
                dtSeconds = dtSeconds,
                impliedSpeedMps = impliedSpeed,
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
        private const val IMPLIED_SPEED_FALLBACK_MAX_ACCURACY_METERS = 15.0
        private const val IMPLIED_SPEED_FALLBACK_MIN_DT_SECONDS = 1.0
        private const val IMPLIED_SPEED_FALLBACK_MIN_SPEED_MPS = 1.5
        private const val STATIONARY_THRESHOLD = 0.55
        private const val OSCILLATION_HEADING_RATE_DEG_PER_SEC = 60.0
        private const val OSCILLATION_JERK_THRESHOLD = 3.0
    }
}
