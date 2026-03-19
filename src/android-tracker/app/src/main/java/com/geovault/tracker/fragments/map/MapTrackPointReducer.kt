package com.geovault.tracker.fragments.map

import com.geovault.tracker.pipeline.TrackPointEvent

internal sealed class MapTrackPointMode {
    data class Single(val displayedTrackerId: String?) : MapTrackPointMode()
    data class Multi(val activeTrackerIds: Set<String>) : MapTrackPointMode()
}

internal data class MapTrackPointState(
    val mode: MapTrackPointMode,
    val modeState: MapModeState
)

internal data class MapTrackPointContext(
    val trackingRunning: Boolean,
    val showAllTrackers: Boolean,
    val mapViewContext: MapViewContext,
    val displayedTrackerId: String?,
    val activeStreamedTrackerIds: Set<String>
)

internal object MapTrackPointReducer {
    fun stateFromContext(context: MapTrackPointContext): MapTrackPointState {
        val mode = if (MapLiveStreamHandler.isMultiContext(context.showAllTrackers, context.mapViewContext)) {
            MapTrackPointMode.Multi(context.activeStreamedTrackerIds)
        } else {
            MapTrackPointMode.Single(context.displayedTrackerId)
        }
        val modeState = MapModeStateMachine.derive(
            MapModeStateInput(
                trackingRunning = context.trackingRunning,
                showAllTrackers = context.showAllTrackers,
                mapViewContext = context.mapViewContext
            )
        )
        return MapTrackPointState(
            mode = mode,
            modeState = modeState
        )
    }

    fun shouldAcceptPoint(event: TrackPointEvent, state: MapTrackPointState): Boolean {
        if (!MapModeStateMachine.acceptsSource(state.modeState, event.source)) return false

        return when (val mode = state.mode) {
            is MapTrackPointMode.Multi -> event.trackId in mode.activeTrackerIds
            is MapTrackPointMode.Single -> MapLiveStreamHandler.shouldHandleSingleTrackPoint(
                trackId = event.trackId,
                displayedTrackerId = mode.displayedTrackerId
            )
        }
    }
}
