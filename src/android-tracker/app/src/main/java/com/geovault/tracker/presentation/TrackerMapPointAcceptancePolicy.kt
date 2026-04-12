package com.geovault.tracker.presentation

import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointSource

private enum class TrackerMapModeState {
    TRACKING_SINGLE,
    BROWSING_SINGLE,
    BROWSING_ALL_TRACKERS,
    BROWSING_GROUP
}

private data class TrackerMapSourcePolicy(
    val acceptLocalGps: Boolean,
    val acceptRemoteStream: Boolean
)

data class TrackerMapPointAcceptanceInput(
    val trackingRunning: Boolean,
    val mode: TrackerMapDisplayMode,
    val displayedTrackerId: String,
    val selectedTrackerId: String,
    val activeStreamedTrackerIds: Set<String>
)

object TrackerMapPointAcceptancePolicy {
    fun shouldAccept(event: TrackPointEvent, input: TrackerMapPointAcceptanceInput): Boolean {
        val modeState = deriveModeState(input)
        if (!acceptsSource(modeState, event.source)) return false

        if (event.source == TrackPointSource.LOCAL_GPS && modeState == TrackerMapModeState.TRACKING_SINGLE) {
            val selectedTrackerId = input.selectedTrackerId
            return if (selectedTrackerId.isBlank()) {
                true
            } else {
                event.trackId == selectedTrackerId
            }
        }

        return when (input.mode) {
            TrackerMapDisplayMode.ALL_QUEUE,
            TrackerMapDisplayMode.GROUP_PLACEHOLDER -> event.trackId in input.activeStreamedTrackerIds
            TrackerMapDisplayMode.SINGLE_SESSION -> event.trackId == input.displayedTrackerId
        }
    }

    private fun deriveModeState(input: TrackerMapPointAcceptanceInput): TrackerMapModeState {
        if (input.trackingRunning) return TrackerMapModeState.TRACKING_SINGLE
        if (input.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) return TrackerMapModeState.BROWSING_GROUP
        if (input.mode == TrackerMapDisplayMode.ALL_QUEUE) return TrackerMapModeState.BROWSING_ALL_TRACKERS
        return TrackerMapModeState.BROWSING_SINGLE
    }

    private fun acceptsSource(state: TrackerMapModeState, source: TrackPointSource): Boolean {
        val sourcePolicy = when (state) {
            TrackerMapModeState.TRACKING_SINGLE -> TrackerMapSourcePolicy(
                acceptLocalGps = true,
                acceptRemoteStream = false
            )
            TrackerMapModeState.BROWSING_SINGLE,
            TrackerMapModeState.BROWSING_ALL_TRACKERS,
            TrackerMapModeState.BROWSING_GROUP -> TrackerMapSourcePolicy(
                acceptLocalGps = false,
                acceptRemoteStream = true
            )
        }
        return when (source) {
            TrackPointSource.LOCAL_GPS -> sourcePolicy.acceptLocalGps
            TrackPointSource.REMOTE_STREAM -> sourcePolicy.acceptRemoteStream
        }
    }
}
