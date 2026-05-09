package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation

data class TrackerMapContextResetInput(
    val state: TrackerMapUiState,
    val preservedSingleTrackerId: String? = null,
)

object TrackerMapContextResetPolicy {
    fun reset(input: TrackerMapContextResetInput): TrackerMapUiState {
        val preservedTrail = preservedSingleTrackerTrail(
            state = input.state,
            trackerId = input.preservedSingleTrackerId,
        )
        return input.state.copy(
            trail = preservedTrail,
            allQueueTrailsByTracker = emptyMap(),
            remoteLastPoints = emptyMap(),
        )
    }

    private fun preservedSingleTrackerTrail(
        state: TrackerMapUiState,
        trackerId: String?,
    ): List<QueuedLocation> {
        val normalizedTrackerId = trackerId?.trim().orEmpty()
        if (normalizedTrackerId.isEmpty()) return emptyList()
        val multiTrail = state.allQueueTrailsByTracker[normalizedTrackerId].orEmpty()
        if (multiTrail.isNotEmpty()) return multiTrail

        val matchingSingleTrail = state.trail.filter { it.trackerId.trim() == normalizedTrackerId }
        if (matchingSingleTrail.isNotEmpty()) return matchingSingleTrail

        val displayedTrackerId = TrackerMapDisplayIds.effectiveDisplayedTrackerId(state).trim()
        return if (displayedTrackerId == normalizedTrackerId) {
            state.trail
        } else {
            emptyList()
        }
    }
}
