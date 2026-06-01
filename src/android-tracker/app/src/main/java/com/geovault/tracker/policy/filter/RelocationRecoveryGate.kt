package com.geovault.tracker.policy.filter

import com.geovault.common.geo.GeoMath
import kotlin.math.max

internal class RelocationRecoveryGate {
    private var state: State? = null

    fun clear() {
        state = null
    }

    fun evaluate(
        input: LocationInput,
        previousAnchor: LocationInput,
        config: LocationFilterConfig,
    ): Decision {
        if (!requiresConfirmation(input = input, previousAnchor = previousAnchor, config = config)) {
            clear()
            return Decision.ContinueRegular
        }
        if (!hasConfirmationQuality(input, config)) {
            return hold(State(candidate = null))
        }

        val candidate = state?.candidate
        if (candidate == null || !isFresh(candidate.input, input, config) || !isConsistent(candidate.input, input, config)) {
            return hold(State(candidate = Candidate(input = input)))
        }

        clear()
        return Decision.Confirmed
    }

    private fun hold(next: State): Decision {
        state = next
        return Decision.Hold
    }

    private fun requiresConfirmation(
        input: LocationInput,
        previousAnchor: LocationInput,
        config: LocationFilterConfig,
    ): Boolean {
        val anchorAgeMs = input.timestampMs - previousAnchor.timestampMs
        if (anchorAgeMs < config.staleAnchorMinAgeMs) return false
        val anchorDistanceMeters = GeoMath.haversineMeters(
            previousAnchor.latitude,
            previousAnchor.longitude,
            input.latitude,
            input.longitude,
        )
        return anchorDistanceMeters >= config.staleAnchorMinDistanceMeters
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

    private data class State(val candidate: Candidate?)

    private data class Candidate(val input: LocationInput)

    sealed interface Decision {
        data object ContinueRegular : Decision
        data object Hold : Decision
        data object Confirmed : Decision
    }
}
