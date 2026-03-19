package com.geovault.tracker.fragments.map

import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker

class LoadAllTrackersMapUseCase(
    private val trackRepository: RuntimeMapTrackRepository,
    private val groupRepository: MapGroupRepository,
    private val visibilityRepository: MapVisibilityRepository
) {
    suspend fun execute(): MapAllTrackersSnapshot {
        val visibility = when (val visibilityResult = visibilityRepository.getMapVisibility()) {
            is RepositoryResult.Success -> visibilityResult.data
            is RepositoryResult.Failure -> null
        }
        val hiddenTrackIds = (visibility?.hidden_track_ids ?: emptyList()).toSet()
        val hiddenGroupIds = (visibility?.hidden_group_ids ?: emptyList()).toSet()

        val groups = when (val groupResult = groupRepository.getGroups(forceRefresh = false)) {
            is RepositoryResult.Success -> groupResult.data
            is RepositoryResult.Failure -> emptyList()
        }
        val hiddenGroupTrackIds = groups
            .filter { it.id in hiddenGroupIds || it.hidden_in_list == true }
            .flatMap { it.track_ids ?: emptyList() }
            .toSet()

        val trackers = when (val trackerResult = trackRepository.getTrackers(forceRefresh = false)) {
            is RepositoryResult.Success -> trackerResult.data
            is RepositoryResult.Failure -> emptyList()
        }
            .filter { it.id !in hiddenTrackIds && it.id !in hiddenGroupTrackIds }

        if (trackers.isEmpty()) {
            return MapAllTrackersSnapshot(trackers = emptyList(), coordsByTrackerId = emptyMap(), fitBounds = false)
        }

        val coordsByTrackerId = when (
            val coordinatesResult = trackRepository.getTrackersCoordinates(trackers.map(Tracker::id), allData = true)
        ) {
            is RepositoryResult.Success -> coordinatesResult.data.mapValues { it.value.coordinates }
            is RepositoryResult.Failure -> emptyMap()
        }

        return MapAllTrackersSnapshot(
            trackers = trackers,
            coordsByTrackerId = coordsByTrackerId,
            fitBounds = true
        )
    }
}

