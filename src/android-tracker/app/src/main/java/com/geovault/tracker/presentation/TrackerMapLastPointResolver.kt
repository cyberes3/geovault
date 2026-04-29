package com.geovault.tracker.presentation

import com.geovault.tracker.Tracker

data class TrackerMapResolvedPoint(
    val latitude: Double,
    val longitude: Double,
    val lastUpdatedMs: Long?,
    val accuracyMeters: Float?,
)

object TrackerMapLastPointResolver {
    fun resolve(
        state: TrackerMapUiState,
        trackerId: String,
        tracker: Tracker?,
    ): TrackerMapResolvedPoint? {
        val normalizedId = trackerId.trim()
        if (normalizedId.isEmpty()) return null
        val eff = TrackerMapDisplayIds.effectiveDisplayedTrackerId(state)
        val remotePoint = state.remoteLastPoints[normalizedId]
        val singleTrailPoint = state.trail.lastOrNull()
            ?.takeIf {
                normalizedId == eff || normalizedId == state.runtime.selectedTrackerId
            }
        val multiTrailPoint = state.allQueueTrailsByTracker[normalizedId]?.lastOrNull()
        val trackerLastPoint = tracker?.last_point
        val latitude = when {
            remotePoint != null -> remotePoint.lat
            singleTrailPoint != null -> singleTrailPoint.latitude
            multiTrailPoint != null -> multiTrailPoint.latitude
            trackerLastPoint != null && trackerLastPoint.size >= 2 -> trackerLastPoint[1]
            else -> return null
        }
        val longitude = when {
            remotePoint != null -> remotePoint.lon
            singleTrailPoint != null -> singleTrailPoint.longitude
            multiTrailPoint != null -> multiTrailPoint.longitude
            trackerLastPoint != null && trackerLastPoint.size >= 2 -> trackerLastPoint[0]
            else -> return null
        }
        return TrackerMapResolvedPoint(
            latitude = latitude,
            longitude = longitude,
            lastUpdatedMs = remotePoint?.timestampMs
                ?: singleTrailPoint?.time
                ?: multiTrailPoint?.time
                ?: tracker?.updated_at,
            accuracyMeters = remotePoint?.accuracyMeters
                ?: singleTrailPoint?.accuracy
                ?: multiTrailPoint?.accuracy,
        )
    }
}
