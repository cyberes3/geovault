package com.geovault.tracker.fragments.map

import com.geovault.tracker.Group
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker

class LoadGroupMapUseCase(
    private val trackRepository: MapTrackRepository
) {
    suspend fun execute(
        group: Group,
        zoomToTrackerId: String? = null
    ): MapAllTrackersSnapshot {
        val groupTrackIds = group.track_ids ?: emptyList()
        if (groupTrackIds.isEmpty()) {
            return MapAllTrackersSnapshot(
                trackers = emptyList(),
                coordsByTrackerId = emptyMap(),
                fitBounds = false
            )
        }

        val trackers = when (val trackersResult = trackRepository.getTrackers(forceRefresh = false)) {
            is RepositoryResult.Success -> trackersResult.data
            is RepositoryResult.Failure -> emptyList()
        }
            .filter { it.id in groupTrackIds }
        val fullTrackers = when (
            val geometryResult = trackRepository.getTrackersGeometry(trackers.map(Tracker::id), allData = true)
        ) {
            is RepositoryResult.Success -> geometryResult.data
            is RepositoryResult.Failure -> emptyList()
        }
        val coordsByTrackerId = fullTrackers.associate { tracker ->
            tracker.id to (tracker.geometry?.coordinates ?: emptyList())
        }
        return MapAllTrackersSnapshot(
            trackers = trackers,
            coordsByTrackerId = coordsByTrackerId,
            fitBounds = true,
            fitToTrackerId = zoomToTrackerId
        )
    }
}

