package com.geovault.tracker.presentation

/**
 * Whether the map chrome should show the my-location (one-shot jump to device location) FAB.
 *
 * Visibility rule: hide the FAB only when the single-session map is showing the **selected**
 * tracker (the user is already centered on their own default view). FAB visibility does **not**
 * depend on tracking / `runtime.isRunning`; the location puck and streaming rules live in
 * [TrackerMapUserLocationPolicy].
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
