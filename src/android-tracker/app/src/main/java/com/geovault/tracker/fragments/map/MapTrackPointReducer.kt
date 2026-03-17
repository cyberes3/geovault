package com.geovault.tracker.fragments.map

import com.geovault.tracker.pipeline.TrackPointEvent
import com.geovault.tracker.pipeline.TrackPointSource

internal sealed class MapTrackPointMode {
    data class Single(val displayedTrackerId: String?) : MapTrackPointMode()
    data class Multi(val activeTrackerIds: Set<String>) : MapTrackPointMode()
}

internal enum class MapTrackPointDataSourceMode {
    LOCAL_GPS_ONLY,
    REMOTE_STREAM_ONLY
}

internal data class MapTrackPointState(
    val mode: MapTrackPointMode,
    val dataSourceMode: MapTrackPointDataSourceMode
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
        val dataSourceMode = if (context.trackingRunning) {
            MapTrackPointDataSourceMode.LOCAL_GPS_ONLY
        } else {
            MapTrackPointDataSourceMode.REMOTE_STREAM_ONLY
        }
        return MapTrackPointState(
            mode = mode,
            dataSourceMode = dataSourceMode
        )
    }

    fun shouldAcceptPoint(event: TrackPointEvent, state: MapTrackPointState): Boolean {
        if (state.dataSourceMode == MapTrackPointDataSourceMode.LOCAL_GPS_ONLY &&
            event.source != TrackPointSource.LOCAL_GPS
        ) return false
        if (state.dataSourceMode == MapTrackPointDataSourceMode.REMOTE_STREAM_ONLY &&
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
