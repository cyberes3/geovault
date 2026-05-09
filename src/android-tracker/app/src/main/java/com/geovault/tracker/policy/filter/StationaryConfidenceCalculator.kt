package com.geovault.tracker.policy.filter

import kotlin.math.max
import kotlin.math.min

/**
 * Pure scoring function that combines the chipset-level signals
 * [LocationMetricsEngine] already tracks (reported speed, implied speed,
 * bearing/speed stability, jerk, accuracy vs displacement) into a single
 * [StationaryConfidence].
 *
 * Stateless and side-effect free so it can be unit tested in isolation
 * and freely swapped if the weighting needs tuning.
 *
 * Weights mirror the well-tested heuristic used by `tslocationmanager`:
 * speed-zero dominates, with stability, jerk, and the
 * accuracy-vs-displacement ratio providing supporting evidence. The
 * weighted sum is clamped to [0, 1] and compared against
 * [StationaryConfidence.STATIONARY_THRESHOLD].
 */
object StationaryConfidenceCalculator {
    /**
     * @param input current location signals.
     * @return [StationaryConfidence.NONE] when the rolling window has
     *   not yet accumulated enough evidence; otherwise a fully-populated
     *   [StationaryConfidence].
     */
    fun evaluate(input: Input): StationaryConfidence {
        if (input.bufferCount <= 0) {
            // First fix in a session: no prior anchor, no displacement
            // history -- nothing to be confident about.
            return StationaryConfidence.NONE
        }

        val speedTerm = (1.0 - min(input.reportedSpeedMps / SPEED_SCALE, 1.0)).coerceIn(0.0, 1.0)
        val impliedTerm = (1.0 - min(input.impliedSpeedMps / SPEED_SCALE, 1.0)).coerceIn(0.0, 1.0)
        val stabilityTerm = (input.bearingStability * 0.5 + input.speedStability * 0.5)
            .coerceIn(0.0, 1.0)
        val jerkTerm = (1.0 - min(input.jerk / JERK_SCALE, 1.0)).coerceIn(0.0, 1.0)
        val accuracyVsDisplacement = if (input.accuracyMeters <= 0.0 || input.rawDistanceMeters <= 0.0) {
            0.5
        } else {
            (input.accuracyMeters / max(input.rawDistanceMeters, 1.0)).coerceIn(0.0, 1.0)
        }

        val score = (
            speedTerm * WEIGHT_SPEED +
                impliedTerm * WEIGHT_IMPLIED_SPEED +
                stabilityTerm * WEIGHT_STABILITY +
                jerkTerm * WEIGHT_JERK +
                accuracyVsDisplacement * WEIGHT_ACCURACY_RATIO
            ).coerceIn(0.0, 1.0)

        val isStationary = score >= StationaryConfidence.STATIONARY_THRESHOLD

        // Rubber-banding signature: confident-stationary AND we still
        // saw real motion in the raw signal AND something is bouncing
        // (heading changing fast OR a sudden velocity discontinuity).
        val isOscillating = isStationary &&
            input.rawDistanceMeters > OSCILLATION_MIN_RAW_METERS &&
            (input.headingChangeRateDegPerSec > OSCILLATION_HEADING_RATE_DEG_PER_SEC ||
                input.jerk > OSCILLATION_JERK_THRESHOLD)

        return StationaryConfidence(
            score = score,
            isStationary = isStationary,
            isOscillating = isOscillating,
        )
    }

    /**
     * Inputs required to score a single fix. Kept as a value class so
     * the engine's per-fix derivation reads as a single argument and
     * unit tests can pin individual signals without mocking the engine.
     */
    data class Input(
        val reportedSpeedMps: Double,
        val impliedSpeedMps: Double,
        val bearingStability: Double,
        val speedStability: Double,
        val jerk: Double,
        val accuracyMeters: Double,
        val rawDistanceMeters: Double,
        val headingChangeRateDegPerSec: Double,
        val bufferCount: Int,
    )

    private const val SPEED_SCALE = 1.5
    private const val JERK_SCALE = 4.0

    private const val WEIGHT_SPEED = 0.30
    private const val WEIGHT_IMPLIED_SPEED = 0.25
    private const val WEIGHT_STABILITY = 0.15
    private const val WEIGHT_JERK = 0.15
    private const val WEIGHT_ACCURACY_RATIO = 0.15

    private const val OSCILLATION_MIN_RAW_METERS = 1.0
    private const val OSCILLATION_HEADING_RATE_DEG_PER_SEC = 60.0
    private const val OSCILLATION_JERK_THRESHOLD = 3.0
}
