package com.geovault.tracker.fragments.map

import android.content.Context
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.SelectedTrackerPrefs
import com.geovault.tracker.Tracker

internal class MapSingleTrackerRuntimeUseCase(
    private val trackRepository: RuntimeMapTrackRepository
) {
    suspend fun execute(
        context: Context,
        trackerId: String,
        forceReplace: Boolean
    ): MapTrackSnapshot {
        val coordinatesResponse = when (
            val result = trackRepository.getTrackerCoordinates(trackerId)
        ) {
            is RepositoryResult.Success -> result.data
            is RepositoryResult.Failure -> null
        }
        val tracker = when (val result = trackRepository.getTracker(trackerId, forceRefresh = false)) {
            is RepositoryResult.Success -> result.data
            is RepositoryResult.Failure -> null
        }
            ?: trackRepository.getTrackerFromCache(trackerId)
            ?: Tracker(id = trackerId, name = SelectedTrackerPrefs.selectedTrackerName(context), color = null)
        return MapTrackSnapshot(
            tracker = tracker,
            coordinates = MapSingleTrackerLoadUtils.mergedCoordinates(
                geometryCoords = emptyList(),
                responseCoords = coordinatesResponse?.coordinates ?: emptyList()
            ),
            forceReplace = forceReplace
        )
    }
}
