package com.geovault.tracker.presentation

/**
 * Whether the map chrome should show the my-location (one-shot jump to device location) FAB.
 *
 * Matches the pre-rewrite intent of `MapFragment.isSelectedDefaultTrackerMode` and the
 * `!isSelectedDefaultTracker` part of `MapMyLocationPolicy.shouldShowButton` in `old android-tracker`
 * (hide only when the single-session map is showing the **selected** tracker). Unlike legacy
 * `MapMyLocationPolicy`, FAB visibility does **not** depend on tracking / `runtime.isRunning`;
 * puck and streaming rules live in [TrackerMapUserLocationPolicy].
 */
object TrackerMapMyLocationFabPolicy {

    /**
     * @param displayedTrackerId raw map UI displayed tracker id (blank means use selected for the effective id, same as MapScreen).
     * @param selectedTrackerId raw runtime selected tracker id.
     */
    fun shouldShowFab(
        mode: TrackerMapDisplayMode,
        displayedTrackerId: String,
        selectedTrackerId: String,
    ): Boolean {
        val selected = selectedTrackerId.trim()
        val effective = displayedTrackerId.trim().ifBlank { selected }
        val isSelectedDefaultSingleSession =
            mode == TrackerMapDisplayMode.SINGLE_SESSION &&
                effective.isNotEmpty() &&
                effective == selected
        return !isSelectedDefaultSingleSession
    }
}
