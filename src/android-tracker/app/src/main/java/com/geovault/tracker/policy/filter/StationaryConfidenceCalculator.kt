package com.geovault.tracker.policy.filter

import kotlin.math.abs

/**
 * Pure stationary scoring function. Stateless and side-effect free for
 * unit testability and easy retuning.
 *
 * Inputs all come from the engine's per-fix scalars. Three hard gates
 * must all pass before scoring begins:
 *
 *  1. `bufferCount >= 3`: requires at least three fixes in the history buffer
 *     before the scorer fires to prevent classifying the first fix in a session.
 *  2. `effectiveDistanceMeters <= 1.0 m`: RSS-corrected anchor-to-fix displacement
 *     must not exceed 1 m; this encodes "GPS geometry says we did not move."
 *  3. `reportedSpeedMps < 1.0 m/s`: GPS Doppler speed (direct velocity measurement
 *     from Doppler shift, unaffected by accuracy inflation) must be below the speed
 *     floor; this ensures no stillness score can be produced while the chipset is
 *     clearly measuring motion. Position geometry is unreliable at low speeds under
 *     inflated accuracy envelopes (UNCERTAINTY_SUPPRESSED); Doppler is trusted when
 *     it clearly shows the device is in motion.
 *
 * Score breakdown:
 *  - `speedZero` (`speed in [0, 1)`): `+0.4` if `speed < 0.5`, else `+0.3`
 *  - `speedStable` (`speedStability > 0.7`): `+ speedStability * 0.2`
 *  - `bearingNoisy` (`bearingStability < 0.3`): `+ (1 - bearingStability) * 0.15`
 *  - `rawClose` (`raw < accuracy * 1.5`): `+0.15`
 *  - `lowJerk` (`abs(jerk) < 0.5`): `+0.10`
 *
 * `isStationary = score >= STATIONARY_THRESHOLD` with one override:
 * if `isOscillating && score > 0.5`, force `isStationary = true`.
 * This catches rubber-band patterns whose score falls in the marginal
 * (0.5, 0.6) band but whose oscillation signature is unambiguous.
 *
 * `isOscillating = bearingNoisy && rawClose && speedZero`.
 */
object StationaryConfidenceCalculator {
    fun evaluate(input: Input): StationaryConfidence {
        if (input.bufferCount < MIN_SAMPLES_FOR_SCORING) return StationaryConfidence.NONE
        if (input.effectiveDistanceMeters > EFFECTIVE_STATIONARY_CEILING_METERS) return StationaryConfidence.NONE
        if (input.reportedSpeedMps >= SPEED_ZERO_CEILING_MPS) return StationaryConfidence.NONE

        val speedZero = input.reportedSpeedMps >= 0.0 && input.reportedSpeedMps < SPEED_ZERO_CEILING_MPS
        val speedStable = input.speedStability > SPEED_STABILITY_FLOOR
        val bearingNoisy = input.bearingStability < BEARING_STABILITY_NOISE_CEILING
        val rawClose = input.rawDistanceMeters < input.accuracyMeters * RAW_CLOSE_ACCURACY_MULTIPLIER
        val lowJerk = abs(input.jerk) < LOW_JERK_CEILING

        val isOscillating = bearingNoisy && rawClose && speedZero

        var score = 0.0
        if (speedZero) {
            score += if (input.reportedSpeedMps < SPEED_HIGH_BONUS_CEILING) {
                SPEED_HIGH_BONUS
            } else {
                SPEED_LOW_BONUS
            }
        }
        if (speedStable) score += input.speedStability * SPEED_STABLE_WEIGHT
        if (bearingNoisy) score += (1.0 - input.bearingStability) * BEARING_NOISY_WEIGHT
        if (rawClose) score += RAW_CLOSE_BONUS
        if (lowJerk) score += LOW_JERK_BONUS
        score = score.coerceIn(0.0, 1.0)

        var isStationary = score >= StationaryConfidence.STATIONARY_THRESHOLD
        if (isOscillating && score > OSCILLATION_OVERRIDE_FLOOR) {
            // Rubber-band patterns whose individual signals don't push
            // the score over the main threshold but whose oscillation
            // signature is unambiguous.
            isStationary = true
        }

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
        val bearingStability: Double,
        val speedStability: Double,
        val jerk: Double,
        val accuracyMeters: Double,
        val rawDistanceMeters: Double,
        val effectiveDistanceMeters: Double,
        val bufferCount: Int,
    )

    private const val MIN_SAMPLES_FOR_SCORING = 3
    private const val EFFECTIVE_STATIONARY_CEILING_METERS = 1.0
    private const val SPEED_ZERO_CEILING_MPS = 1.0
    private const val SPEED_HIGH_BONUS_CEILING = 0.5
    private const val SPEED_HIGH_BONUS = 0.4
    private const val SPEED_LOW_BONUS = 0.3
    private const val SPEED_STABILITY_FLOOR = 0.7
    private const val SPEED_STABLE_WEIGHT = 0.2
    private const val BEARING_STABILITY_NOISE_CEILING = 0.3
    private const val BEARING_NOISY_WEIGHT = 0.15
    private const val RAW_CLOSE_ACCURACY_MULTIPLIER = 1.5
    private const val RAW_CLOSE_BONUS = 0.15
    private const val LOW_JERK_CEILING = 0.5
    private const val LOW_JERK_BONUS = 0.10
    private const val OSCILLATION_OVERRIDE_FLOOR = 0.5
}
