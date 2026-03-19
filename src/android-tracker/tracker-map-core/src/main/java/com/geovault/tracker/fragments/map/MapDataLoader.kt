package com.geovault.tracker.fragments.map

import org.maplibre.android.geometry.LatLng

object MapDataLoader {
    fun resolveActiveSingleTrackerId(
        trackingRunning: Boolean,
        displayedTrackerId: String?,
        selectedTrackerId: String
    ): String {
        if (trackingRunning) return selectedTrackerId
        return displayedTrackerId?.takeIf { it.isNotEmpty() } ?: selectedTrackerId
    }

    fun isExternalStreaming(
        forceReplace: Boolean,
        hasTrackPoints: Boolean,
        displayedTrackerId: String?
    ): Boolean {
        return !forceReplace && hasTrackPoints && displayedTrackerId != null
    }

    fun shouldAutoZoomSingleTracker(trackPointsEmpty: Boolean): Boolean {
        return trackPointsEmpty
    }

    /**
     * Resolve the single-tracker camera zoom target. Prefer the latest rendered point so the
     * chevron target matches what is drawn, and fall back to tracker.last_point when needed.
     */
    fun resolveSingleTrackerZoomTarget(
        trackPoints: List<LatLng>,
        fallbackLastPoint: List<Double>?
    ): LatLng? {
        trackPoints.lastOrNull()?.let { return it }
        val lp = fallbackLastPoint ?: return null
        if (lp.size < 2) return null
        return LatLng(lp[1], lp[0])
    }
}

