package com.geovault.tracker.presentation

import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
import com.geovault.tracker.db.QueuedLocation

object TrackerMapTrailDataCoordinator {
    suspend fun loadSingleTrackerTrail(
        trackerId: String,
        loadTrackerGeometry: suspend (String) -> RepositoryResult<Tracker>,
        loadQueueTrailWithOverlay: suspend () -> List<QueuedLocation>,
        resolveSessionStartMs: (List<Map<String, Any?>>?) -> Long?,
        onSessionStartResolved: (String, Long?) -> Unit,
        onSessionAnchorResolved: (String) -> Unit,
        mapCoordinatesToTrail: (List<List<Double>>, List<Map<String, Any?>>?) -> List<QueuedLocation>,
    ): List<QueuedLocation> {
        val geometryResult = loadTrackerGeometry(trackerId)
        val geometryCoords = when (geometryResult) {
            is RepositoryResult.Success -> geometryResult.data.geometry?.coordinates.orEmpty()
            is RepositoryResult.Failure -> emptyList()
        }
        val pointParams = when (geometryResult) {
            is RepositoryResult.Success -> geometryResult.data.point_params
            is RepositoryResult.Failure -> null
        }
        onSessionStartResolved(trackerId, resolveSessionStartMs(pointParams))
        onSessionAnchorResolved(trackerId)
        return if (geometryCoords.isEmpty()) {
            loadQueueTrailWithOverlay()
        } else {
            mapCoordinatesToTrail(geometryCoords, pointParams)
        }
    }

    suspend fun loadTrailsForTrackerIds(
        trackerIds: Collection<String>,
        loadTrackersGeometry: suspend (List<String>) -> RepositoryResult<List<Tracker>>,
        resolveSessionStartMs: (List<Map<String, Any?>>?) -> Long?,
        onSessionStartResolved: (String, Long?) -> Unit,
        mapCoordinatesToTrail: (List<List<Double>>, List<Map<String, Any?>>?) -> List<QueuedLocation>,
    ): Map<String, List<QueuedLocation>> {
        val normalizedIds = trackerIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (normalizedIds.isEmpty()) return emptyMap()
        return when (val result = loadTrackersGeometry(normalizedIds)) {
            is RepositoryResult.Success -> {
                result.data.associate { tracker ->
                    onSessionStartResolved(
                        tracker.id,
                        resolveSessionStartMs(tracker.point_params)
                    )
                    tracker.id to mapCoordinatesToTrail(
                        tracker.geometry?.coordinates.orEmpty(),
                        tracker.point_params
                    )
                }
            }
            is RepositoryResult.Failure -> emptyMap()
        }
    }
}
