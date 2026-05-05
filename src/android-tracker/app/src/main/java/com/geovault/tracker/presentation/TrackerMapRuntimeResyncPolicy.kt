package com.geovault.tracker.presentation

internal enum class TrackerMapRuntimeTransition {
    STARTED,
    STOPPED,
    NONE
}

internal data class TrackerMapRuntimeResyncDecision(
    val transition: TrackerMapRuntimeTransition,
    val restartTrackPointStream: Boolean,
    val restartDisplayedStreaming: Boolean
)

internal class TrackerMapRuntimeResyncPolicy {
    fun decide(
        previousIsRunning: Boolean?,
        currentIsRunning: Boolean,
        mapReady: Boolean,
        mapViewContext: TrackerMapViewContext,
    ): TrackerMapRuntimeResyncDecision {
        val transition = when {
            previousIsRunning == null -> TrackerMapRuntimeTransition.NONE
            !previousIsRunning && currentIsRunning -> TrackerMapRuntimeTransition.STARTED
            previousIsRunning && !currentIsRunning -> TrackerMapRuntimeTransition.STOPPED
            else -> TrackerMapRuntimeTransition.NONE
        }
        return when (transition) {
            TrackerMapRuntimeTransition.STARTED -> TrackerMapRuntimeResyncDecision(
                transition = transition,
                restartTrackPointStream = true,
                restartDisplayedStreaming = mapReady && (
                    mapViewContext == TrackerMapViewContext.SINGLE_TRACKER ||
                        mapViewContext == TrackerMapViewContext.GROUP
                    ),
            )
            TrackerMapRuntimeTransition.STOPPED,
            TrackerMapRuntimeTransition.NONE -> TrackerMapRuntimeResyncDecision(
                transition = transition,
                restartTrackPointStream = false,
                restartDisplayedStreaming = false
            )
        }
    }
}
