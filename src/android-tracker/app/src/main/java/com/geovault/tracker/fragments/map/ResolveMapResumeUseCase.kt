package com.geovault.tracker.fragments.map

internal data class MapResumeInput(
    val trackingRunning: Boolean,
    val mapReady: Boolean,
    val showAllTrackers: Boolean,
    val mapViewContext: MapViewContext,
    val activeStreamedTrackerIds: Set<String>,
    val currentGroupTrackIds: Set<String>,
    val selectedTrackerId: String,
    val displayedTrackerId: String?,
    val hasTrackPoints: Boolean,
    val hasPendingInitialTracker: Boolean
)

internal sealed class MapResumeDecision {
    data object NoOp : MapResumeDecision()
    data object MultiContextNoStreaming : MapResumeDecision()
    data class StartMultiContextStreaming(val trackerIds: Set<String>) : MapResumeDecision()
    data object ClearSingleTrackerState : MapResumeDecision()
    data class LoadSingleTracker(val trackerId: String) : MapResumeDecision()
    data object RestartDisplayedTrackerStreaming : MapResumeDecision()
}

internal class ResolveMapResumeUseCase {
    fun resolve(input: MapResumeInput): MapResumeDecision {
        if (!input.mapReady) return MapResumeDecision.NoOp

        if (input.mapViewContext == MapViewContext.GROUP || input.showAllTrackers) {
            return when {
                input.activeStreamedTrackerIds.isNotEmpty() ->
                    MapResumeDecision.StartMultiContextStreaming(input.activeStreamedTrackerIds)
                input.mapViewContext == MapViewContext.GROUP && input.currentGroupTrackIds.isNotEmpty() ->
                    MapResumeDecision.StartMultiContextStreaming(input.currentGroupTrackIds)
                else -> MapResumeDecision.MultiContextNoStreaming
            }
        }

        val activeTrackerId = MapDataLoader.resolveActiveSingleTrackerId(
            trackingRunning = input.trackingRunning,
            displayedTrackerId = input.displayedTrackerId,
            selectedTrackerId = input.selectedTrackerId
        )

        if (activeTrackerId.isEmpty() && !input.hasPendingInitialTracker) {
            return MapResumeDecision.ClearSingleTrackerState
        }
        if (input.trackingRunning && activeTrackerId.isNotEmpty()) {
            return MapResumeDecision.LoadSingleTracker(activeTrackerId)
        }
        if (!input.hasTrackPoints && activeTrackerId.isNotEmpty()) {
            return MapResumeDecision.LoadSingleTracker(activeTrackerId)
        }
        return MapResumeDecision.RestartDisplayedTrackerStreaming
    }
}
