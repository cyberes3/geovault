package com.geovault.tracker.fragments.map

internal enum class MapListRefreshAction {
    NO_OP,
    LOAD_ALL,
    REFRESH_SELECTED_TRACKER
}

internal object MapListRefreshPolicy {
    fun resolve(
        showAllTrackers: Boolean,
        mapViewContext: MapViewContext,
        selectedTrackerId: String,
        displayedTrackerId: String?
    ): MapListRefreshAction {
        if (showAllTrackers && mapViewContext != MapViewContext.GROUP) {
            return MapListRefreshAction.LOAD_ALL
        }
        if (!showAllTrackers && mapViewContext != MapViewContext.GROUP && selectedTrackerId != displayedTrackerId) {
            return MapListRefreshAction.REFRESH_SELECTED_TRACKER
        }
        return MapListRefreshAction.NO_OP
    }
}
