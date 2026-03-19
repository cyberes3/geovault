package com.geovault.tracker.fragments.map

import com.geovault.tracker.pipeline.TrackPointEvent
import com.geovault.tracker.pipeline.MapDataSourceMode
import com.geovault.tracker.pipeline.TrackPointSourceResolver
import com.geovault.tracker.pipeline.TrackPointSource

internal sealed class MapTrackPointMode {
    data class Single(val displayedTrackerId: String?) : MapTrackPointMode()
    data class Multi(val activeTrackerIds: Set<String>) : MapTrackPointMode()
}

internal data class MapTrackPointState(
    val mode: MapTrackPointMode,
    val dataSourceMode: MapDataSourceMode
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
        val dataSourceMode = TrackPointSourceResolver.mapDataSourceMode(context.trackingRunning)
        return MapTrackPointState(
            mode = mode,
            dataSourceMode = dataSourceMode
        )
    }

    fun shouldAcceptPoint(event: TrackPointEvent, state: MapTrackPointState): Boolean {
        if (state.dataSourceMode == MapDataSourceMode.LOCAL_GPS_ONLY &&
            event.source != TrackPointSource.LOCAL_GPS
        ) return false
        if (state.dataSourceMode == MapDataSourceMode.REMOTE_STREAM_ONLY &&
            event.source != TrackPointSource.REMOTE_STREAM
        ) return false

        return when (val mode = state.mode) {
            is MapTrackPointMode.Multi -> event.trackId in mode.activeTrackerIds
            is MapTrackPointMode.Single -> MapLiveStreamHandler.shouldHandleSingleTrackPoint(
                trackId = event.trackId,
                displayedTrackerId = mode.displayedTrackerId
            )
        }
    }
}
