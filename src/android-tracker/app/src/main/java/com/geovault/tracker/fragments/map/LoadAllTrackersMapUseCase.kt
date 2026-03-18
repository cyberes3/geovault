package com.geovault.tracker.fragments.map

import android.content.Context
import com.geovault.tracker.Tracker

class LoadAllTrackersMapUseCase(
    private val trackRepository: MapTrackRepository,
    private val groupRepository: MapGroupRepository,
    private val visibilityRepository: MapVisibilityRepository
) {
    suspend fun execute(context: Context): MapAllTrackersSnapshot {
        val visibility = visibilityRepository.getMapVisibility(context)
        val hiddenTrackIds = (visibility?.hidden_track_ids ?: emptyList()).toSet()
        val hiddenGroupIds = (visibility?.hidden_group_ids ?: emptyList()).toSet()

        val groups = groupRepository.getGroups(context, forceRefresh = false)
        val hiddenGroupTrackIds = groups
            .filter { it.id in hiddenGroupIds || it.hidden_in_list == true }
            .flatMap { it.track_ids ?: emptyList() }
            .toSet()

        val trackers = trackRepository.getTrackers(context, forceRefresh = false)
            .filter { it.id !in hiddenTrackIds && it.id !in hiddenGroupTrackIds }

        if (trackers.isEmpty()) {
            return MapAllTrackersSnapshot(trackers = emptyList(), coordsByTrackerId = emptyMap(), fitBounds = false)
        }

        val fullTrackers = trackRepository.getTrackersGeometry(context, trackers.map(Tracker::id), allData = true)
        val coordsByTrackerId = fullTrackers.associate { tracker ->
            tracker.id to (tracker.geometry?.coordinates ?: emptyList())
        }

        return MapAllTrackersSnapshot(
            trackers = trackers,
            coordsByTrackerId = coordsByTrackerId,
            fitBounds = true
        )
    }
}

