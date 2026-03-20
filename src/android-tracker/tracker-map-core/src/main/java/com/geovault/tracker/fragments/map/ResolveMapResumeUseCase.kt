package com.geovault.tracker.fragments.map

data class MapResumeInput(
    val trackingRunning: Boolean,
    val mapReady: Boolean,
    val showAllTrackers: Boolean,
    val mapViewContext: MapViewContext,
    val activeStreamedTrackerIds: Set<String>,
    val currentGroupTrackIds: Set<String>,
    val selectedTrackerId: String,
    val displayedTrackerId: String?,
    val hasTrackPoints: Boolean,
    val hasPendingInitialTracker: Boolean,
    val backgroundedDurationMs: Long
)

sealed class MapResumeDecision {
    data object NoOp : MapResumeDecision()
    data object MultiContextNoStreaming : MapResumeDecision()
    data class StartMultiContextStreaming(val trackerIds: Set<String>) : MapResumeDecision()
    data object ClearSingleTrackerState : MapResumeDecision()
    data class LoadSingleTrackerRuntime(val trackerId: String) : MapResumeDecision()
    data class LoadSingleTrackerBootstrap(val trackerId: String) : MapResumeDecision()
    data object RestartDisplayedTrackerStreaming : MapResumeDecision()
}

class ResolveMapResumeUseCase {
    private companion object {
        // If the app stayed backgrounded long enough, force a geometry backfill before streaming resumes.
        // This closes potential websocket gap windows after doze/network disconnect periods.
        const val BACKFILL_MIN_BACKGROUND_MS = 15_000L
    }

    fun resolve(input: MapResumeInput): MapResumeDecision {
        if (!input.mapReady) return MapResumeDecision.NoOp

        if (input.trackingRunning) {
            val selectedTrackerId = input.selectedTrackerId.takeIf { it.isNotEmpty() }
            val streamedWithoutSelected = input.activeStreamedTrackerIds.filterTo(mutableSetOf()) { id ->
                id.isNotEmpty() && id != selectedTrackerId
            }
            if (input.mapViewContext == MapViewContext.GROUP || input.showAllTrackers) {
                val fallbackGroupIds = input.currentGroupTrackIds.filterTo(mutableSetOf()) { id ->
                    id.isNotEmpty() && id != selectedTrackerId
                }
                return when {
                    streamedWithoutSelected.isNotEmpty() ->
                        MapResumeDecision.StartMultiContextStreaming(streamedWithoutSelected)
                    input.mapViewContext == MapViewContext.GROUP && fallbackGroupIds.isNotEmpty() ->
                        MapResumeDecision.StartMultiContextStreaming(fallbackGroupIds)
                    !selectedTrackerId.isNullOrEmpty() ->
                        MapResumeDecision.LoadSingleTrackerRuntime(selectedTrackerId)
                    else -> MapResumeDecision.MultiContextNoStreaming
                }
            }

            val displayedTrackerId = input.displayedTrackerId?.takeIf { it.isNotEmpty() }
            val activeSingleTrackerId = displayedTrackerId ?: selectedTrackerId.orEmpty()
            if (activeSingleTrackerId.isEmpty() && !input.hasPendingInitialTracker) {
                return MapResumeDecision.ClearSingleTrackerState
            }
            if (input.hasTrackPoints && displayedTrackerId == activeSingleTrackerId) {
                // Keep in-memory track state when returning to the same tracker context.
                return MapResumeDecision.NoOp
            }
            if (displayedTrackerId != null && displayedTrackerId in streamedWithoutSelected) {
                return MapResumeDecision.RestartDisplayedTrackerStreaming
            }
            if (activeSingleTrackerId.isNotEmpty() && activeSingleTrackerId == selectedTrackerId) {
                // Selected tracker may need runtime hydration after process/view recreation.
                return MapResumeDecision.LoadSingleTrackerRuntime(activeSingleTrackerId)
            }
            return MapResumeDecision.NoOp
        }

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
        val displayedTrackerId = input.displayedTrackerId?.takeIf { it.isNotEmpty() }
        if (activeTrackerId.isNotEmpty() && displayedTrackerId != activeTrackerId) {
            // If selected/default tracker differs from current display context (or there is no
            // display context), restore single-tracker context explicitly instead of trying to
            // restart streaming against a missing/old displayed tracker id.
            val isStreamBootstrap = activeTrackerId in input.activeStreamedTrackerIds
            return if (isStreamBootstrap) {
                MapResumeDecision.LoadSingleTrackerBootstrap(activeTrackerId)
            } else {
                MapResumeDecision.LoadSingleTrackerRuntime(activeTrackerId)
            }
        }
        if (!input.hasTrackPoints && activeTrackerId.isNotEmpty()) {
            val isStreamBootstrap = activeTrackerId in input.activeStreamedTrackerIds
            return if (isStreamBootstrap) {
                MapResumeDecision.LoadSingleTrackerBootstrap(activeTrackerId)
            } else {
                MapResumeDecision.LoadSingleTrackerRuntime(activeTrackerId)
            }
        }
        if (!input.trackingRunning &&
            input.hasTrackPoints &&
            activeTrackerId.isNotEmpty() &&
            input.backgroundedDurationMs >= BACKFILL_MIN_BACKGROUND_MS
        ) {
            val isStreamBootstrap = activeTrackerId in input.activeStreamedTrackerIds
            return if (isStreamBootstrap) {
                MapResumeDecision.LoadSingleTrackerBootstrap(activeTrackerId)
            } else {
                MapResumeDecision.LoadSingleTrackerRuntime(activeTrackerId)
            }
        }
        return MapResumeDecision.RestartDisplayedTrackerStreaming
    }
}

