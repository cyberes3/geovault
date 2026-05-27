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
    ): TrackerMapServerTrailResult {
        val normalizedId = trackerId.trim()
        return when (val geometryResult = loadTrackerGeometry(trackerId)) {
            is RepositoryResult.Success -> {
                TrackerMapServerTrailResult(
                    trailsByTracker = mapOf(
                        normalizedId to mapCoordinatesToTrail(
                            trackerId,
                            geometryResult.data.geometry?.coordinates.orEmpty(),
                            geometryResult.data.point_params,
                            existingTrailMinTimeMs
                        )
                    ),
                    authoritativeTrackerIds = setOf(normalizedId).filter { it.isNotEmpty() }.toSet(),
                )
            }
            is RepositoryResult.Failure -> TrackerMapServerTrailResult(
                trailsByTracker = mapOf(normalizedId to loadQueueTrail()).filterKeys { it.isNotEmpty() },
                authoritativeTrackerIds = emptySet(),
            )
        }
    }

    suspend fun loadTrailsForTrackerIds(
        trackerIds: Collection<String>,
        existingTrailMinTimeMsByTracker: Map<String, Long>,
        loadTrackersGeometry: suspend (List<String>) -> RepositoryResult<List<Tracker>>,
        loadQueueTrail: suspend (String) -> List<QueuedLocation>,
        mapCoordinatesToTrail: (String, List<List<Double>>, List<Map<String, Any?>>?, Long?) -> List<QueuedLocation>,
    ): TrackerMapServerTrailResult {
        val normalizedIds = trackerIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (normalizedIds.isEmpty()) {
            return TrackerMapServerTrailResult(
                trailsByTracker = emptyMap(),
                authoritativeTrackerIds = emptySet(),
            )
        }
        return when (val result = loadTrackersGeometry(normalizedIds)) {
            is RepositoryResult.Success -> {
                val trackersById = result.data.associateBy { it.id.trim() }
                val authoritativeIds = trackersById.keys.intersect(normalizedIds.toSet())
                val trails = normalizedIds.associateWith { trackerId ->
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
                TrackerMapServerTrailResult(
                    trailsByTracker = trails,
                    authoritativeTrackerIds = authoritativeIds,
                )
            }
            is RepositoryResult.Failure -> {
                TrackerMapServerTrailResult(
                    trailsByTracker = normalizedIds.associateWith { trackerId -> loadQueueTrail(trackerId) },
                    authoritativeTrackerIds = emptySet(),
                )
            }
        }
    }
}
