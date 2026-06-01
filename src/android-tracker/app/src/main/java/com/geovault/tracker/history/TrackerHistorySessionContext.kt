package com.geovault.tracker.history

import com.geovault.tracker.Tracker

/**
 * Session-scoped inputs for a single history compose pass.
 */
data class TrackerHistorySessionContext(
    val activeSessionStartMs: Long?,
    val window: TrackerHistoryWindow,
    val skipRenderWindowFilter: Boolean = false,
) {
    fun resolveEffectiveSessionStartMs(
        overlayBatches: List<TrackerHistorySourceBatch>,
        clearBoundary: TrackerHistoryClearBoundary?,
    ): Long? {
        activeSessionStartMs?.let { return it }
        if (!window.isCurrentSession && !window.isSession) return null
        val trackerId = clearBoundary?.trackerId?.trim().orEmpty()
        return overlayBatches
            .flatMap { it.points }
            .asSequence()
            .filter { point ->
                if (trackerId.isNotEmpty() && point.trackerId.trim() != trackerId) {
                    return@filter false
                }
                point.startTimestampMs != null && point.isAfterClearBoundary(clearBoundary)
            }
            .mapNotNull { it.startTimestampMs }
            .maxOrNull()
    }

    private fun TrackerHistoryPoint.isAfterClearBoundary(boundary: TrackerHistoryClearBoundary?): Boolean {
        if (boundary == null) return true
        if (trackerId.trim() != boundary.trackerId.trim()) return true
        val afterClearTime = timestampMs >= boundary.clearedAtMs
        val matchesActiveSession = boundary.activeSessionStartMs != null &&
            startTimestampMs == boundary.activeSessionStartMs
        return afterClearTime || matchesActiveSession
    }
}

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
