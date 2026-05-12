package com.geovault.tracker.policy.filter

import com.geovault.common.geo.GeoMath
import kotlin.math.max

internal class ResumeAnchorGate {
    private var state: State? = null

    val isActive: Boolean get() = state != null

    fun start() {
        state = State()
    }

    fun clear() {
        state = null
    }

    fun evaluate(input: LocationInput, metrics: LocationMetrics, config: LocationFilterConfig): Decision {
        val current = state ?: return Decision.Inactive
        if (metrics.rawDistanceMeters < config.resumeConfirmationMinDistanceMeters) {
            clear()
            return Decision.ContinueRegular
        }
        if (!hasConfirmationQuality(input, config)) {
            return hold(current.copy(candidate = null))
        }

        val candidate = current.candidate
        if (candidate == null || !isFresh(candidate, input, config) || !isConsistent(candidate.input, input, config)) {
            return hold(current.copy(candidate = Candidate(input = input)))
        }

        clear()
        return Decision.Confirmed
    }

    private fun hold(next: State): Decision {
        state = next
        return Decision.Hold
    }

    private fun hasConfirmationQuality(input: LocationInput, config: LocationFilterConfig): Boolean {
        val accuracy = input.accuracyMeters?.toDouble() ?: return false
        return accuracy <= config.resumeConfirmationMaxAccuracyMeters
    }

    private fun isFresh(candidate: Candidate, input: LocationInput, config: LocationFilterConfig): Boolean {
        return input.timestampMs - candidate.input.timestampMs in 0L..config.resumeConfirmationWindowMs
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

    private data class State(val candidate: Candidate? = null)

    private data class Candidate(val input: LocationInput)

    sealed interface Decision {
        data object Inactive : Decision
        data object ContinueRegular : Decision
        data object Hold : Decision
        data object Confirmed : Decision
    }
}
