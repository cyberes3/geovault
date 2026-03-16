package com.geovault.tracker.fragments.map

import com.geovault.tracker.Tracker

internal object MapLiveActiveFitController {
    fun resolveTrackerLastUpdateMsForGroupFit(
        tracker: Tracker,
        normalizedCoords: List<List<Double>>?,
        lastKnownUpdateByTrackerId: Map<String, Long>,
        trackerLastUpdateMs: (Tracker) -> Long?
    ): Long? {
        val fromCoords = normalizedCoords
            ?.lastOrNull()
            ?.let { MapCoordinateUtils.timestampFromCoordinateMs(it) }
            ?.takeIf { it > 0L }
        return fromCoords
            ?: lastKnownUpdateByTrackerId[tracker.id]
            ?: trackerLastUpdateMs(tracker)
    }

    fun isLiveActiveFitAvailable(
        showAllTrackers: Boolean,
        mapViewContext: MapViewContext,
        hasTrackPoints: Boolean
    ): Boolean {
        val singleTrackerVisible = !showAllTrackers &&
            (mapViewContext == MapViewContext.DEFAULT_TRACKER || mapViewContext == MapViewContext.SPECIFIC_TRACKER) &&
            hasTrackPoints
        return showAllTrackers || mapViewContext == MapViewContext.GROUP || singleTrackerVisible
    }

    fun isLiveActiveFitToggleEnabled(
        available: Boolean,
        showAllTrackers: Boolean,
        mapViewContext: MapViewContext,
        showMyLocationEnabled: Boolean
    ): Boolean {
        if (!available) return false
        val singleTrackerMode = !showAllTrackers &&
            (mapViewContext == MapViewContext.DEFAULT_TRACKER || mapViewContext == MapViewContext.SPECIFIC_TRACKER)
        return !singleTrackerMode || showMyLocationEnabled
    }
}
