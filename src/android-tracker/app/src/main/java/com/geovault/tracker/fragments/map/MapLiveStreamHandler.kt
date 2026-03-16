package com.geovault.tracker.fragments.map

internal object MapLiveStreamHandler {
    fun isMultiContext(showAllTrackers: Boolean, mapViewContext: MapViewContext): Boolean {
        return showAllTrackers || mapViewContext == MapViewContext.GROUP
    }

    fun shouldHandleSingleTrackPoint(
        trackId: String,
        displayedTrackerId: String?,
        selectedTrackerId: String?
    ): Boolean {
        val expectedTrackId = displayedTrackerId ?: selectedTrackerId
        return !expectedTrackId.isNullOrEmpty() && trackId == expectedTrackId
    }
}
