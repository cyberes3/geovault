package com.geovault.tracker.presentation

import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointSource

data class TrackerMapPointAcceptanceInput(
    val trackingRunning: Boolean,
    val mode: TrackerMapDisplayMode,
    val displayedTrackerId: String,
    val selectedTrackerId: String,
    val activeStreamedTrackerIds: Set<String>
)

object TrackerMapPointAcceptancePolicy {
    fun shouldAccept(event: TrackPointEvent, input: TrackerMapPointAcceptanceInput): Boolean {
        val eventTrackerId = event.trackId.trim()
        if (eventTrackerId.isEmpty()) return false
        val selectedTrackerId = input.selectedTrackerId.trim()
        val displayedTrackerId = input.displayedTrackerId.trim()

        if (event.source == TrackPointSource.LOCAL_GPS) {
            return acceptsLocalGps(
                eventTrackerId = eventTrackerId,
                selectedTrackerId = selectedTrackerId,
                displayedTrackerId = displayedTrackerId,
                trackingRunning = input.trackingRunning,
                mode = input.mode,
            )
        }

        return acceptsRemoteStream(
            eventTrackerId = eventTrackerId,
            selectedTrackerId = selectedTrackerId,
            displayedTrackerId = displayedTrackerId,
            trackingRunning = input.trackingRunning,
            mode = input.mode,
            activeStreamedTrackerIds = input.activeStreamedTrackerIds,
        )
    }

    private fun acceptsLocalGps(
        eventTrackerId: String,
        selectedTrackerId: String,
        displayedTrackerId: String,
        trackingRunning: Boolean,
        mode: TrackerMapDisplayMode,
    ): Boolean {
        if (!trackingRunning) return false
        if (selectedTrackerId.isNotBlank() && eventTrackerId != selectedTrackerId) return false
        return when (mode) {
            TrackerMapDisplayMode.ALL_QUEUE,
            TrackerMapDisplayMode.GROUP_PLACEHOLDER -> true
            TrackerMapDisplayMode.SINGLE_SESSION -> {
                displayedTrackerId.isBlank() ||
                    selectedTrackerId.isBlank() ||
                    displayedTrackerId == selectedTrackerId
            }
        }
    }

    private fun acceptsRemoteStream(
        eventTrackerId: String,
        selectedTrackerId: String,
        displayedTrackerId: String,
        trackingRunning: Boolean,
        mode: TrackerMapDisplayMode,
        activeStreamedTrackerIds: Set<String>,
    ): Boolean {
        if (trackingRunning && selectedTrackerId.isNotBlank() && eventTrackerId == selectedTrackerId) {
            return false
        }
        return when (mode) {
            TrackerMapDisplayMode.ALL_QUEUE,
            TrackerMapDisplayMode.GROUP_PLACEHOLDER -> eventTrackerId in activeStreamedTrackerIds
            TrackerMapDisplayMode.SINGLE_SESSION -> eventTrackerId == displayedTrackerId
        }
    }
}
