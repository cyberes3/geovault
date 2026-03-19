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
    val hasPendingInitialTracker: Boolean,
    val backgroundedDurationMs: Long
)

internal sealed class MapResumeDecision {
    data object NoOp : MapResumeDecision()
    data object MultiContextNoStreaming : MapResumeDecision()
    data class StartMultiContextStreaming(val trackerIds: Set<String>) : MapResumeDecision()
    data object ClearSingleTrackerState : MapResumeDecision()
    data class LoadSingleTracker(
        val trackerId: String,
        val coordinatesOnly: Boolean = false
    ) : MapResumeDecision()
    data object RestartDisplayedTrackerStreaming : MapResumeDecision()
}

internal class ResolveMapResumeUseCase {
    private companion object {
        // If the app stayed backgrounded long enough, force a geometry backfill before streaming resumes.
        // This closes potential websocket gap windows after doze/network disconnect periods.
        const val BACKFILL_MIN_BACKGROUND_MS = 15_000L
    }

    fun resolve(input: MapResumeInput): MapResumeDecision {
        if (!input.mapReady) return MapResumeDecision.NoOp

        if (input.trackingRunning) {
            val activeTrackerId = MapDataLoader.resolveActiveSingleTrackerId(
                trackingRunning = true,
                displayedTrackerId = input.displayedTrackerId,
                selectedTrackerId = input.selectedTrackerId
            )
            if (activeTrackerId.isEmpty() && !input.hasPendingInitialTracker) {
                return MapResumeDecision.ClearSingleTrackerState
            }
            if (input.displayedTrackerId == activeTrackerId && input.hasTrackPoints) {
                // Keep live in-memory track state when user returns to map during active tracking.
                return MapResumeDecision.NoOp
            }
            // Tracking runtime should never reload historical geometry.
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
        val coordinatesOnly = activeTrackerId.isNotEmpty() && activeTrackerId in input.activeStreamedTrackerIds
        if (!input.hasTrackPoints && activeTrackerId.isNotEmpty()) {
            return MapResumeDecision.LoadSingleTracker(
                trackerId = activeTrackerId,
                coordinatesOnly = coordinatesOnly
            )
        }
        if (!input.trackingRunning &&
            input.hasTrackPoints &&
            activeTrackerId.isNotEmpty() &&
            input.backgroundedDurationMs >= BACKFILL_MIN_BACKGROUND_MS
        ) {
            return MapResumeDecision.LoadSingleTracker(
                trackerId = activeTrackerId,
                coordinatesOnly = coordinatesOnly
            )
        }
        return MapResumeDecision.RestartDisplayedTrackerStreaming
    }
}
