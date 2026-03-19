package com.geovault.tracker.pipeline

import com.geovault.tracker.fragments.map.MapModeStateInput
import com.geovault.tracker.fragments.map.MapModeStateMachine
import com.geovault.tracker.fragments.map.MapViewContext

object TrackPointSourceResolver {
    fun mapDataSourceMode(trackingRunning: Boolean): MapDataSourceMode {
        val state = MapModeStateMachine.derive(
            MapModeStateInput(
                trackingRunning = trackingRunning,
                showAllTrackers = false,
                mapViewContext = MapViewContext.SINGLE_TRACKER
            )
        )
        return if (MapModeStateMachine.sourcePolicy(state).acceptLocalGps) {
            MapDataSourceMode.LOCAL_GPS_ONLY
        } else {
            MapDataSourceMode.REMOTE_STREAM_ONLY
        }
    }

    fun shouldAcceptForParams(
        event: TrackPointEvent,
        trackerId: String,
        trackingRunning: Boolean,
        selectedTrackerId: String
    ): Boolean {
        if (event.trackId != trackerId) return false
        val state = MapModeStateMachine.derive(
            MapModeStateInput(
                trackingRunning = trackingRunning && trackerId == selectedTrackerId,
                showAllTrackers = false,
                mapViewContext = MapViewContext.SINGLE_TRACKER
            )
        )
        return MapModeStateMachine.acceptsSource(state, event.source)
    }
}

enum class MapDataSourceMode {
    LOCAL_GPS_ONLY,
    REMOTE_STREAM_ONLY
}
