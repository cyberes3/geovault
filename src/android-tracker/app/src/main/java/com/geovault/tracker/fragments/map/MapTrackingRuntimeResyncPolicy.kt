package com.geovault.tracker.fragments.map

internal enum class MapTrackingRuntimeTransition {
    NONE,
    STARTED,
    STOPPED
}

internal data class MapTrackingRuntimeResyncDecision(
    val transition: MapTrackingRuntimeTransition,
    val restartTrackPointStream: Boolean,
    val restartDisplayedStreaming: Boolean
)

/**
 * Determines when the map should proactively re-sync runtime-dependent streams.
 */
internal class MapTrackingRuntimeResyncPolicy {
    fun decide(
        previousIsRunning: Boolean?,
        currentIsRunning: Boolean,
        mapReady: Boolean,
        mapViewContext: MapViewContext
    ): MapTrackingRuntimeResyncDecision {
        val transition = when {
            previousIsRunning == null -> MapTrackingRuntimeTransition.NONE
            !previousIsRunning && currentIsRunning -> MapTrackingRuntimeTransition.STARTED
            previousIsRunning && !currentIsRunning -> MapTrackingRuntimeTransition.STOPPED
            else -> MapTrackingRuntimeTransition.NONE
        }
        return when (transition) {
            MapTrackingRuntimeTransition.STARTED -> MapTrackingRuntimeResyncDecision(
                transition = transition,
                restartTrackPointStream = true,
                restartDisplayedStreaming = mapReady && mapViewContext == MapViewContext.SINGLE_TRACKER
            )
            MapTrackingRuntimeTransition.STOPPED,
            MapTrackingRuntimeTransition.NONE -> MapTrackingRuntimeResyncDecision(
                transition = transition,
                restartTrackPointStream = false,
                restartDisplayedStreaming = false
            )
        }
    }
}
