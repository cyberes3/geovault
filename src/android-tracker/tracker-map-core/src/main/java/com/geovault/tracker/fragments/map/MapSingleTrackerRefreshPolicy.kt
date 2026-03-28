package com.geovault.tracker.fragments.map

enum class MapSingleTrackerRefreshTrigger {
    STANDARD,
    SETTINGS_CHANGE,
    HISTORY_CLEAR
}

data class MapSingleTrackerRefreshInput(
    val trackingActive: Boolean,
    val hasTrackPoints: Boolean,
    val forceReplace: Boolean,
    val trigger: MapSingleTrackerRefreshTrigger
)

data class MapSingleTrackerRefreshDecision(
    val shouldFetch: Boolean,
    val shouldPrimeSessionAnchorResync: Boolean
)

object MapSingleTrackerRefreshPolicy {
    fun resolve(input: MapSingleTrackerRefreshInput): MapSingleTrackerRefreshDecision {
        if (input.forceReplace) {
            return MapSingleTrackerRefreshDecision(
                shouldFetch = true,
                shouldPrimeSessionAnchorResync =
                input.trigger == MapSingleTrackerRefreshTrigger.SETTINGS_CHANGE ||
                    input.trigger == MapSingleTrackerRefreshTrigger.HISTORY_CLEAR
            )
        }
        if (input.trackingActive && input.hasTrackPoints) {
            return MapSingleTrackerRefreshDecision(
                shouldFetch = false,
                shouldPrimeSessionAnchorResync = false
            )
        }
        return MapSingleTrackerRefreshDecision(
            shouldFetch = true,
            shouldPrimeSessionAnchorResync = false
        )
    }
}
