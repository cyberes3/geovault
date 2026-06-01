package com.geovault.tracker.policy.filter

import com.geovault.common.geo.GeoMath

internal class RelocationRecoveryGate {
    private val confirmationGate = SpatialConfirmationGate()

    fun clear() {
        confirmationGate.clear()
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
        return when (confirmationGate.evaluate(input, config)) {
            SpatialConfirmationGate.Decision.Hold -> Decision.Hold
            SpatialConfirmationGate.Decision.Confirmed -> {
                clear()
                Decision.Confirmed
            }
        }
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

    sealed interface Decision {
        data object ContinueRegular : Decision
        data object Hold : Decision
        data object Confirmed : Decision
    }
}
