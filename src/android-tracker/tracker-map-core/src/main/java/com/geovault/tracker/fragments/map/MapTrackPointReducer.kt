package com.geovault.tracker.fragments.map

import com.geovault.tracker.pipeline.TrackPointEvent
import com.geovault.tracker.pipeline.TrackPointSource

sealed class MapTrackPointMode {
    data class Single(val displayedTrackerId: String?) : MapTrackPointMode()
    data class Multi(val activeTrackerIds: Set<String>) : MapTrackPointMode()
}

data class MapTrackPointState(
    val mode: MapTrackPointMode,
    val modeState: MapModeState,
    val selectedTrackerId: String?
)

data class MapTrackPointContext(
    val trackingRunning: Boolean,
    val showAllTrackers: Boolean,
    val mapViewContext: MapViewContext,
    val displayedTrackerId: String?,
    val selectedTrackerId: String?,
    val activeStreamedTrackerIds: Set<String>
)

object MapTrackPointReducer {
    fun stateFromContext(context: MapTrackPointContext): MapTrackPointState {
        val modeState = MapModeStateMachine.derive(
            MapModeStateInput(
                trackingRunning = context.trackingRunning,
                showAllTrackers = context.showAllTrackers,
                mapViewContext = context.mapViewContext
            )
        )
        val mode = if (MapLiveStreamHandler.isMultiContext(context.showAllTrackers, context.mapViewContext)) {
            MapTrackPointMode.Multi(context.activeStreamedTrackerIds)
        } else {
            MapTrackPointMode.Single(context.displayedTrackerId)
        }
        return MapTrackPointState(
            mode = mode,
            modeState = modeState,
            selectedTrackerId = context.selectedTrackerId
        )
    }

    fun shouldAcceptPoint(event: TrackPointEvent, state: MapTrackPointState): Boolean {
        if (!MapModeStateMachine.acceptsSource(state.modeState, event.source)) return false
        if (event.source == TrackPointSource.LOCAL_GPS && state.modeState == MapModeState.TRACKING_SINGLE) {
            val selectedTrackerId = state.selectedTrackerId
            return if (selectedTrackerId.isNullOrBlank()) {
                true
            } else {
                event.trackId == selectedTrackerId
            }
        }

        return when (val mode = state.mode) {
            is MapTrackPointMode.Multi -> event.trackId in mode.activeTrackerIds
            is MapTrackPointMode.Single -> MapLiveStreamHandler.shouldHandleSingleTrackPoint(
                trackId = event.trackId,
                displayedTrackerId = mode.displayedTrackerId
            )
        }
    }
}

