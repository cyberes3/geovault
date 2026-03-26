package com.geovault.tracker.fragments.map

import com.geovault.tracker.pipeline.TrackPointSource

enum class MapModeState {
    TRACKING_SINGLE,
    BROWSING_SINGLE,
    BROWSING_ALL_TRACKERS,
    BROWSING_GROUP
}

enum class MapModeEvent {
    ENTER_TRACKING,
    EXIT_TRACKING,
    SHOW_SINGLE,
    SHOW_ALL_TRACKERS,
    SHOW_GROUP
}

data class MapModeStateInput(
    val trackingRunning: Boolean,
    val showAllTrackers: Boolean,
    val mapViewContext: MapViewContext
)

data class MapSourcePolicy(
    val acceptLocalGps: Boolean,
    val acceptRemoteStream: Boolean
)

object MapModeStateMachine {
    fun derive(input: MapModeStateInput): MapModeState {
        if (input.trackingRunning) return MapModeState.TRACKING_SINGLE
        if (input.mapViewContext == MapViewContext.GROUP) return MapModeState.BROWSING_GROUP
        if (input.showAllTrackers) return MapModeState.BROWSING_ALL_TRACKERS
        return MapModeState.BROWSING_SINGLE
    }

    fun sourcePolicy(state: MapModeState): MapSourcePolicy {
        return when (state) {
            MapModeState.TRACKING_SINGLE -> MapSourcePolicy(
                acceptLocalGps = true,
                acceptRemoteStream = false
            )
            MapModeState.BROWSING_SINGLE,
            MapModeState.BROWSING_ALL_TRACKERS,
            MapModeState.BROWSING_GROUP -> MapSourcePolicy(
                acceptLocalGps = false,
                acceptRemoteStream = true
            )
        }
    }

    fun acceptsSource(state: MapModeState, source: TrackPointSource): Boolean {
        val policy = sourcePolicy(state)
        return when (source) {
            TrackPointSource.LOCAL_GPS -> policy.acceptLocalGps
            TrackPointSource.REMOTE_STREAM -> policy.acceptRemoteStream
        }
    }

    fun transition(current: MapModeState, event: MapModeEvent): MapModeState {
        return when (event) {
            MapModeEvent.ENTER_TRACKING -> MapModeState.TRACKING_SINGLE
            MapModeEvent.EXIT_TRACKING -> when (current) {
                MapModeState.BROWSING_ALL_TRACKERS -> MapModeState.BROWSING_ALL_TRACKERS
                MapModeState.BROWSING_GROUP -> MapModeState.BROWSING_GROUP
                else -> MapModeState.BROWSING_SINGLE
            }
            MapModeEvent.SHOW_SINGLE -> if (current == MapModeState.TRACKING_SINGLE) {
                MapModeState.TRACKING_SINGLE
            } else {
                MapModeState.BROWSING_SINGLE
            }
            MapModeEvent.SHOW_ALL_TRACKERS -> if (current == MapModeState.TRACKING_SINGLE) {
                MapModeState.TRACKING_SINGLE
            } else {
                MapModeState.BROWSING_ALL_TRACKERS
            }
            MapModeEvent.SHOW_GROUP -> if (current == MapModeState.TRACKING_SINGLE) {
                MapModeState.TRACKING_SINGLE
            } else {
                MapModeState.BROWSING_GROUP
            }
        }
    }
}

