package com.geovault.tracker.fragments.map

object MapLiveStreamHandler {
    fun isMultiContext(showAllTrackers: Boolean, mapViewContext: MapViewContext): Boolean {
        return showAllTrackers || mapViewContext == MapViewContext.GROUP
    }

    fun shouldHandleSingleTrackPoint(trackId: String, displayedTrackerId: String?): Boolean {
        return trackId == displayedTrackerId
    }
}

