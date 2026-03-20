package com.geovault.tracker.fragments.map

import com.geovault.tracker.Group
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker

class LoadGroupMapUseCase(
    private val trackRepository: RuntimeMapTrackRepository
) {
    data class Result(
        val snapshot: MapAllTrackersSnapshot,
        val hadFailures: Boolean
    )

    suspend fun execute(
        group: Group,
        zoomToTrackerId: String? = null
    ): Result {
        var hadFailures = false
        val groupTrackIds = group.track_ids ?: emptyList()
        if (groupTrackIds.isEmpty()) {
            return Result(
                snapshot = MapAllTrackersSnapshot(
                    trackers = emptyList(),
                    coordsByTrackerId = emptyMap(),
                    fitBounds = false
                ),
                hadFailures = false
            )
        }

        val trackers = when (val trackersResult = trackRepository.getTrackers(forceRefresh = false)) {
            is RepositoryResult.Success -> trackersResult.data
            is RepositoryResult.Failure -> {
                hadFailures = true
                emptyList()
            }
        }
            .filter { it.id in groupTrackIds }
        val coordsByTrackerId = when (
            val coordinatesResult = trackRepository.getTrackersCoordinates(trackers.map(Tracker::id))
        ) {
            is RepositoryResult.Success -> coordinatesResult.data.mapValues { it.value.coordinates }
            is RepositoryResult.Failure -> {
                hadFailures = true
                emptyMap()
            }
        }
        return Result(
            snapshot = MapAllTrackersSnapshot(
                trackers = trackers,
                coordsByTrackerId = coordsByTrackerId,
                fitBounds = true,
                fitToTrackerId = zoomToTrackerId
            ),
            hadFailures = hadFailures
        )
    }
}

