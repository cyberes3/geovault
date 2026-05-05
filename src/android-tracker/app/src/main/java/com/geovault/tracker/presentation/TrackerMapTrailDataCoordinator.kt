package com.geovault.tracker.presentation

import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
import com.geovault.tracker.db.QueuedLocation

object TrackerMapTrailDataCoordinator {
    suspend fun loadSingleTrackerTrail(
        trackerId: String,
        existingTrailMinTimeMs: Long?,
        loadTrackerGeometry: suspend (String) -> RepositoryResult<Tracker>,
        loadQueueTrail: suspend () -> List<QueuedLocation>,
        mapCoordinatesToTrail: (String, List<List<Double>>, List<Map<String, Any?>>?, Long?) -> List<QueuedLocation>,
    ): List<QueuedLocation> {
        return when (val geometryResult = loadTrackerGeometry(trackerId)) {
            is RepositoryResult.Success -> {
                mapCoordinatesToTrail(
                    trackerId,
                    geometryResult.data.geometry?.coordinates.orEmpty(),
                    geometryResult.data.point_params,
                    existingTrailMinTimeMs
                )
            }
            is RepositoryResult.Failure -> loadQueueTrail()
        }
    }

    suspend fun loadTrailsForTrackerIds(
        trackerIds: Collection<String>,
        existingTrailMinTimeMsByTracker: Map<String, Long>,
        loadTrackersGeometry: suspend (List<String>) -> RepositoryResult<List<Tracker>>,
        loadQueueTrail: suspend (String) -> List<QueuedLocation>,
        mapCoordinatesToTrail: (String, List<List<Double>>, List<Map<String, Any?>>?, Long?) -> List<QueuedLocation>,
    ): Map<String, List<QueuedLocation>> {
        val normalizedIds = trackerIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (normalizedIds.isEmpty()) return emptyMap()
        return when (val result = loadTrackersGeometry(normalizedIds)) {
            is RepositoryResult.Success -> {
                val trackersById = result.data.associateBy { it.id.trim() }
                normalizedIds.associateWith { trackerId ->
                    val tracker = trackersById[trackerId]
                    if (tracker == null) {
                        loadQueueTrail(trackerId)
                    } else {
                        mapCoordinatesToTrail(
                            trackerId,
                            tracker.geometry?.coordinates.orEmpty(),
                            tracker.point_params,
                            existingTrailMinTimeMsByTracker[trackerId]
                        )
                    }
                }
            }
            is RepositoryResult.Failure -> {
                normalizedIds.associateWith { trackerId -> loadQueueTrail(trackerId) }
            }
        }
    }
}
