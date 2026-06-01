package com.geovault.tracker.policy.filter

internal class ResumeAnchorGate {
    private var active: Boolean = false
    private val confirmationGate = SpatialConfirmationGate()

    val isActive: Boolean get() = active

    fun start() {
        active = true
        confirmationGate.clear()
    }

    fun clear() {
        active = false
        confirmationGate.clear()
    }

    fun evaluate(input: LocationInput, metrics: LocationMetrics, config: LocationFilterConfig): Decision {
        if (!active) {
            return Decision.Inactive
        }
        if (metrics.rawDistanceMeters < config.resumeConfirmationMinDistanceMeters) {
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

    sealed interface Decision {
        data object Inactive : Decision
        data object ContinueRegular : Decision
        data object Hold : Decision
        data object Confirmed : Decision
    }
}
