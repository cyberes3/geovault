package com.geovault.tracker.fragments.map

/**
 * Determines when the map should proactively re-sync runtime-dependent streams.
 */
internal class MapTrackingRuntimeResyncPolicy {
    fun decide(
        previousIsRunning: Boolean?,
        currentIsRunning: Boolean,
        mapReady: Boolean,
        mapViewContext: MapViewContext
    ): MapRuntimeResyncCommand {
        val transition = when {
            previousIsRunning == null -> MapRuntimeTransition.NONE
            !previousIsRunning && currentIsRunning -> MapRuntimeTransition.STARTED
            previousIsRunning && !currentIsRunning -> MapRuntimeTransition.STOPPED
            else -> MapRuntimeTransition.NONE
        }
        return when (transition) {
            MapRuntimeTransition.STARTED -> MapRuntimeResyncCommand(
                transition = transition,
                restartTrackPointStream = true,
                restartDisplayedStreaming = mapReady && mapViewContext == MapViewContext.SINGLE_TRACKER
            )
            MapRuntimeTransition.STOPPED,
            MapRuntimeTransition.NONE -> MapRuntimeResyncCommand(
                transition = transition,
                restartTrackPointStream = false,
                restartDisplayedStreaming = false
            )
        }
    }
}
