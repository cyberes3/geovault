package com.geovault.tracker.presentation

object TrackerMapDisplayIds {
    fun effectiveDisplayedTrackerId(state: TrackerMapUiState): String {
        return state.displayedTrackerId.trim().ifBlank { state.runtime.selectedTrackerId.trim() }
    }
}
