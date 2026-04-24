package com.geovault.tracker.presentation

import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
import com.geovault.tracker.db.QueuedLocation

object TrackerMapTrailDataCoordinator {
    suspend fun loadSingleTrackerTrail(
        trackerId: String,
        existingTrailMinTimeMs: Long?,
        loadTrackerGeometry: suspend (String) -> RepositoryResult<Tracker>,
        loadQueueTrailWithOverlay: suspend () -> List<QueuedLocation>,
        mapCoordinatesToTrail: (String, List<List<Double>>, List<Map<String, Any?>>?, Long?) -> List<QueuedLocation>,
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
        return if (geometryCoords.isEmpty()) {
            loadQueueTrailWithOverlay()
        } else {
            mapCoordinatesToTrail(trackerId, geometryCoords, pointParams, existingTrailMinTimeMs)
        }
    }

    suspend fun loadTrailsForTrackerIds(
        trackerIds: Collection<String>,
        existingTrailMinTimeMsByTracker: Map<String, Long>,
        loadTrackersGeometry: suspend (List<String>) -> RepositoryResult<List<Tracker>>,
        mapCoordinatesToTrail: (String, List<List<Double>>, List<Map<String, Any?>>?, Long?) -> List<QueuedLocation>,
    ): Map<String, List<QueuedLocation>> {
        val normalizedIds = trackerIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (normalizedIds.isEmpty()) return emptyMap()
        return when (val result = loadTrackersGeometry(normalizedIds)) {
            is RepositoryResult.Success -> {
                result.data.associate { tracker ->
                    tracker.id to mapCoordinatesToTrail(
                        tracker.id,
                        tracker.geometry?.coordinates.orEmpty(),
                        tracker.point_params,
                        existingTrailMinTimeMsByTracker[tracker.id]
                    )
                }
            }
            is RepositoryResult.Failure -> emptyMap()
        }
    }
}
