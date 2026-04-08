package com.geovault.tracker.presentation

import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerCoordinatesResponse
import com.geovault.tracker.db.QueuedLocation
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

object TrackerMapTrailDataCoordinator {
    suspend fun loadSingleTrackerTrail(
        trackerId: String,
        loadTrackerCoordinates: suspend (String) -> RepositoryResult<TrackerCoordinatesResponse>,
        loadTrackerGeometry: suspend (String) -> RepositoryResult<Tracker>,
        loadQueueTrailWithOverlay: suspend () -> List<QueuedLocation>,
        resolveSessionStartMs: (List<Map<String, Any?>>?) -> Long?,
        onSessionStartResolved: (String, Long?) -> Unit,
        onSessionAnchorResolved: (String) -> Unit,
        mergeCoordinates: (List<List<Double>>, List<List<Double>>) -> List<List<Double>>,
        mapCoordinatesToTrail: (List<List<Double>>) -> List<QueuedLocation>,
    ): List<QueuedLocation> {
        val (coordinatesResponse, geometryResult) = coroutineScope {
            val coordinatesDeferred = async { loadTrackerCoordinates(trackerId) }
            val geometryDeferred = async { loadTrackerGeometry(trackerId) }
            val resolvedCoordinates = when (val response = coordinatesDeferred.await()) {
                is RepositoryResult.Success -> response.data.coordinates
                is RepositoryResult.Failure -> emptyList()
            }
            resolvedCoordinates to geometryDeferred.await()
        }
        val geometryCoords = when (geometryResult) {
            is RepositoryResult.Success -> geometryResult.data.geometry?.coordinates.orEmpty()
            is RepositoryResult.Failure -> emptyList()
        }
        val pointParams = when (geometryResult) {
            is RepositoryResult.Success -> geometryResult.data.point_params
            is RepositoryResult.Failure -> null
        }
        onSessionStartResolved(trackerId, resolveSessionStartMs(pointParams))
        val merged = mergeCoordinates(geometryCoords, coordinatesResponse)
        onSessionAnchorResolved(trackerId)
        return if (merged.isEmpty()) {
            loadQueueTrailWithOverlay()
        } else {
            mapCoordinatesToTrail(merged)
        }
    }

    suspend fun loadTrailsForTrackerIds(
        trackerIds: Collection<String>,
        loadTrackersGeometry: suspend (List<String>) -> RepositoryResult<List<Tracker>>,
        loadTrackerCoordinates: suspend (String) -> RepositoryResult<TrackerCoordinatesResponse>,
        resolveSessionStartMs: (List<Map<String, Any?>>?) -> Long?,
        onSessionStartResolved: (String, Long?) -> Unit,
        mergeCoordinates: (List<List<Double>>, List<List<Double>>) -> List<List<Double>>,
        mapCoordinatesToTrail: (List<List<Double>>) -> List<QueuedLocation>,
    ): Map<String, List<QueuedLocation>> {
        val normalizedIds = trackerIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (normalizedIds.isEmpty()) return emptyMap()
        return when (val result = loadTrackersGeometry(normalizedIds)) {
            is RepositoryResult.Success -> {
                val coordinatesById = loadCoordinatesForTrackerIds(
                    trackerIds = normalizedIds,
                    loadTrackerCoordinates = loadTrackerCoordinates
                )
                result.data.associate { tracker ->
                    onSessionStartResolved(
                        tracker.id,
                        resolveSessionStartMs(tracker.point_params)
                    )
                    val merged = mergeCoordinates(
                        tracker.geometry?.coordinates.orEmpty(),
                        coordinatesById[tracker.id].orEmpty()
                    )
                    tracker.id to mapCoordinatesToTrail(merged)
                }
            }
            is RepositoryResult.Failure -> emptyMap()
        }
    }

    private suspend fun loadCoordinatesForTrackerIds(
        trackerIds: Collection<String>,
        loadTrackerCoordinates: suspend (String) -> RepositoryResult<TrackerCoordinatesResponse>,
    ): Map<String, List<List<Double>>> {
        return coroutineScope {
            trackerIds.associateWith { trackerId ->
                async {
                    when (val response = loadTrackerCoordinates(trackerId)) {
                        is RepositoryResult.Success -> response.data.coordinates
                        is RepositoryResult.Failure -> emptyList()
                    }
                }
            }.mapValues { (_, deferred) ->
                deferred.await()
            }
        }
    }
}
