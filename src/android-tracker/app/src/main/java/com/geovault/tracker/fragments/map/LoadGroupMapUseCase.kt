package com.geovault.tracker.fragments.map

import android.content.Context
import com.geovault.tracker.Group
import com.geovault.tracker.Tracker

class LoadGroupMapUseCase(
    private val trackRepository: MapTrackRepository
) {
    suspend fun execute(
        context: Context,
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

        val trackers = trackRepository.getTrackers(context, forceRefresh = false)
            .filter { it.id in groupTrackIds }
        val fullTrackers = trackRepository.getTrackersGeometry(context, trackers.map(Tracker::id), allData = true)
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

