package com.geovault.tracker.history

import com.geovault.tracker.Tracker

/**
 * Session-scoped inputs for a single history compose pass.
 */
data class TrackerHistorySessionContext(
    val activeSessionStartMs: Long?,
    val window: TrackerHistoryWindow,
    val skipRenderWindowFilter: Boolean = false,
)

object TrackerHistoryRenderWindowPolicy {
    /**
     * When true, [TrackerHistoryAssembler] does not apply the client recent-data window filter
     * because the server trunk is already window-complete for the selected settings window.
     */
    fun shouldSkipRenderWindowFilter(
        complete: Boolean,
        degradedLocalOnly: Boolean,
        window: TrackerHistoryWindow,
        geometryStatusWindow: String?,
    ): Boolean {
        if (!complete || degradedLocalOnly) return false
        if (window.isCurrentSession || window.isSession) return false
        val statusWindow = geometryStatusWindow?.trim()?.lowercase().orEmpty()
        if (statusWindow.isEmpty()) return true
        return statusWindow == window.normalizedKey
    }

    fun shouldSkipRenderWindowFilter(
        snapshot: TrackerHistorySnapshot?,
        tracker: Tracker?,
        window: TrackerHistoryWindow,
    ): Boolean {
        if (snapshot == null || !snapshot.complete || snapshot.degradedLocalOnly) return false
        val settingsWindow = TrackerHistoryWindowResolver.fromTracker(tracker)
        if (settingsWindow.isCurrentSession || settingsWindow.isSession) return false
        return shouldSkipRenderWindowFilter(
            complete = snapshot.complete,
            degradedLocalOnly = snapshot.degradedLocalOnly,
            window = window,
            geometryStatusWindow = tracker?.geometry_status?.window,
        )
    }
}
