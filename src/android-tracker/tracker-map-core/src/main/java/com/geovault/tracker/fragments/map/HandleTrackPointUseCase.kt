package com.geovault.tracker.fragments.map

import com.geovault.tracker.pipeline.TrackPointEvent

class HandleTrackPointUseCase {
    fun shouldAccept(
        event: TrackPointEvent,
        trackingRunning: Boolean,
        showAllTrackers: Boolean,
        mapViewContext: MapViewContext,
        displayedTrackerId: String?,
        selectedTrackerId: String?,
        activeStreamedTrackerIds: Set<String>
    ): Boolean {
        val state = MapTrackPointReducer.stateFromContext(
            MapTrackPointContext(
                trackingRunning = trackingRunning,
                showAllTrackers = showAllTrackers,
                mapViewContext = mapViewContext,
                displayedTrackerId = displayedTrackerId,
                selectedTrackerId = selectedTrackerId,
                activeStreamedTrackerIds = activeStreamedTrackerIds
            )
        )
        return MapTrackPointReducer.shouldAcceptPoint(event = event, state = state)
    }
}

