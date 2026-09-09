package com.geovault.tracker.presentation

internal object TrackerMapDisplayIds {
    fun effectiveDisplayedTrackerId(
        displayedTrackerId: String,
        selectedTrackerId: String,
    ): String {
        return displayedTrackerId.trim().ifBlank { selectedTrackerId.trim() }
    }

    fun effectiveDisplayedTrackerId(
        state: TrackerMapUiState,
        selectedTrackerId: String,
    ): String {
        return effectiveDisplayedTrackerId(state.displayedTrackerId, selectedTrackerId)
    }
}
