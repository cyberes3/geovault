package com.geovault.tracker

import org.maplibre.android.geometry.LatLng
import java.util.Collections

/**
 * Helper to update track points and timestamps in sync, ensuring chronological order
 * and enforcing the 1000-point sliding window.
 */
object TrackUpdateHelper {

    const val MAX_POINTS = 1000

    fun updateTrack(
        trackPoints: MutableList<LatLng>,
        trackTimestamps: MutableList<Long>,
        latLng: LatLng,
        timestamp: Long
    ) {
        // Binary search to find the correct insertion point to keep it chronologically sorted
        val index = Collections.binarySearch(trackTimestamps, timestamp)
        val insertionPoint = if (index < 0) -(index + 1) else index
        
        if (insertionPoint < trackPoints.size && trackTimestamps[insertionPoint] == timestamp) {
            // Replace existing point with same timestamp (rare but possible)
            trackPoints[insertionPoint] = latLng
        } else {
            trackPoints.add(insertionPoint, latLng)
            trackTimestamps.add(insertionPoint, timestamp)
        }
        
        // Enforce sliding window
        while (trackPoints.size > MAX_POINTS) {
            trackPoints.removeAt(0)
            trackTimestamps.removeAt(0)
        }
    }
}
