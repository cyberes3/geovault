package com.geovault.tracker.fragments.map

import com.geovault.tracker.pipeline.TrackPointEvent

internal class HandleTrackPointUseCase {
    fun shouldAccept(
        event: TrackPointEvent,
        trackingRunning: Boolean,
        showAllTrackers: Boolean,
        mapViewContext: MapViewContext,
        displayedTrackerId: String?,
        activeStreamedTrackerIds: Set<String>
    ): Boolean {
        val state = MapTrackPointReducer.stateFromContext(
            MapTrackPointContext(
                trackingRunning = trackingRunning,
                showAllTrackers = showAllTrackers,
                mapViewContext = mapViewContext,
                displayedTrackerId = displayedTrackerId,
                activeStreamedTrackerIds = activeStreamedTrackerIds
            )
        )
        return MapTrackPointReducer.shouldAcceptPoint(event = event, state = state)
    }
}

