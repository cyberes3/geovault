package com.geovault.tracker.policy.filter

import com.geovault.common.geo.GeoMath
import kotlin.math.max

/**
 * Shared twin-fix spatial confirmation used after motion resume and stale relocation.
 */
internal class SpatialConfirmationGate {
    private var candidateInput: LocationInput? = null

    fun clear() {
        candidateInput = null
    }

    fun evaluate(input: LocationInput, config: LocationFilterConfig): Decision {
        if (!hasConfirmationQuality(input, config)) {
            candidateInput = null
            return Decision.Hold
        }

        val candidate = candidateInput
        if (candidate == null || !isFresh(candidate, input, config) || !isConsistent(candidate, input, config)) {
            candidateInput = input
            return Decision.Hold
        }

        clear()
        return Decision.Confirmed
    }

    private fun hasConfirmationQuality(input: LocationInput, config: LocationFilterConfig): Boolean {
        val accuracy = input.accuracyMeters?.toDouble() ?: return false
        return accuracy <= config.resumeConfirmationMaxAccuracyMeters
    }

    private fun isFresh(candidate: LocationInput, input: LocationInput, config: LocationFilterConfig): Boolean {
        return input.timestampMs - candidate.timestampMs in 0L..config.resumeConfirmationWindowMs
    }

    private fun isConsistent(candidate: LocationInput, input: LocationInput, config: LocationFilterConfig): Boolean {
        val candidateAccuracy = candidate.accuracyMeters?.toDouble() ?: 0.0
        val inputAccuracy = input.accuracyMeters?.toDouble() ?: 0.0
        val allowance = max(
            config.resumeConfirmationConsistencyMeters,
            candidateAccuracy + inputAccuracy,
        )
        val distanceMeters = GeoMath.haversineMeters(
            candidate.latitude,
            candidate.longitude,
            input.latitude,
            input.longitude,
        )
        return distanceMeters <= allowance
    }

    sealed interface Decision {
        data object Hold : Decision
        data object Confirmed : Decision
    }
}
