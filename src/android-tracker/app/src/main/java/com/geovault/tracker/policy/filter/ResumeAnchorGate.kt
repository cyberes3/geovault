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
        // A single accurate fix very far from the pre-pause anchor is unambiguous
        // evidence of relocation. Requiring a twin-fix confirmation is impossible at
        // highway speeds where the fix interval exceeds the confirmation window and
        // the inter-fix distance dwarfs the spatial consistency threshold.
        if (config.resumeConfirmationLargeDisplacementMeters > 0 &&
            metrics.rawDistanceMeters >= config.resumeConfirmationLargeDisplacementMeters
        ) {
            clear()
            return Decision.Confirmed
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
