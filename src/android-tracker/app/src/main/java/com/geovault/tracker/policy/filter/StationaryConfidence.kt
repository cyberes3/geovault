package com.geovault.tracker.policy.filter

/**
 * Multi-signal stationary classification produced by
 * [StationaryConfidenceCalculator]. Consumers read `metrics.stationary.*`
 * so [score], [isStationary], and [isOscillating] can never disagree.
 *
 * @property score 0..1 weighted evidence of standstill (1.0 = certain).
 * @property isStationary true iff [score] is above
 *   [STATIONARY_THRESHOLD]. Used by [LocationFilter] to suppress GPS
 *   jitter while standing still.
 * @property isOscillating true iff [isStationary] is true AND the fix
 *   exhibits the textbook rubber-banding signature (non-trivial raw
 *   distance with bouncing bearing or accuracy). Strict subset of
 *   [isStationary].
 */
data class StationaryConfidence(
    val score: Double,
    val isStationary: Boolean,
    val isOscillating: Boolean,
) {
    companion object {
        /**
         * Threshold above which we treat the score as confident
         * standstill. 0.60 leaves a clear margin above the marginal
         * 0.55 region and prevents snap/accept flicker on borderline
         * fixes.
         */
        const val STATIONARY_THRESHOLD = 0.60

        /** Neutral value used before enough samples accumulate. */
        val NONE = StationaryConfidence(score = 0.0, isStationary = false, isOscillating = false)
    }
}
