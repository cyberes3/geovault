package com.geovault.tracker.fragments.map

import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker

class LoadAllTrackersMapUseCase(
    private val trackRepository: RuntimeMapTrackRepository,
    private val groupRepository: MapGroupRepository,
    private val visibilityRepository: MapVisibilityRepository
) {
    data class Result(
        val snapshot: MapAllTrackersSnapshot,
        val hadFailures: Boolean
    )

    suspend fun execute(): Result {
        var hadFailures = false
        val visibility = when (val visibilityResult = visibilityRepository.getMapVisibility()) {
            is RepositoryResult.Success -> visibilityResult.data
            is RepositoryResult.Failure -> {
                hadFailures = true
                null
            }
        }
        val hiddenTrackIds = (visibility?.hidden_track_ids ?: emptyList()).toSet()
        val hiddenGroupIds = (visibility?.hidden_group_ids ?: emptyList()).toSet()

        val groups = when (val groupResult = groupRepository.getGroups(forceRefresh = false)) {
            is RepositoryResult.Success -> groupResult.data
            is RepositoryResult.Failure -> {
                hadFailures = true
                emptyList()
            }
        }
        val hiddenGroupTrackIds = groups
            .filter { it.id in hiddenGroupIds || it.hidden_in_list == true }
            .flatMap { it.track_ids ?: emptyList() }
            .toSet()

        val trackers = when (val trackerResult = trackRepository.getTrackers(forceRefresh = false)) {
            is RepositoryResult.Success -> trackerResult.data
            is RepositoryResult.Failure -> {
                hadFailures = true
                emptyList()
            }
        }
            .filter { it.id !in hiddenTrackIds && it.id !in hiddenGroupTrackIds }
            .filter { !(it.isOwner() && (it.settings?.get("hidden_in_list") as? Boolean) == true) }

        if (trackers.isEmpty()) {
            return Result(
                snapshot = MapAllTrackersSnapshot(trackers = emptyList(), coordsByTrackerId = emptyMap(), fitBounds = false),
                hadFailures = hadFailures
            )
        }

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
                fitBounds = true
            ),
            hadFailures = hadFailures
        )
    }
}

