package com.geovault.tracker.fragments.map

import android.content.Context
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.SelectedTrackerPrefs
import com.geovault.tracker.Tracker

internal class MapSingleTrackerBootstrapUseCase(
    private val trackRepository: BootstrapMapTrackRepository
) {
    suspend fun execute(
        context: Context,
        trackerId: String,
        forceReplace: Boolean
    ): MapTrackSnapshot {
        val coordinatesResponse = when (
            val result = trackRepository.getTrackerCoordinates(trackerId, allData = true)
        ) {
            is RepositoryResult.Success -> result.data
            is RepositoryResult.Failure -> null
        }
        val geometryTracker = when (val result = trackRepository.getTrackerGeometry(trackerId, allData = true)) {
            is RepositoryResult.Success -> result.data
            is RepositoryResult.Failure -> null
        }
        if (geometryTracker != null) {
            return MapTrackSnapshot(
                tracker = geometryTracker,
                coordinates = MapSingleTrackerLoadUtils.mergedCoordinates(
                    geometryCoords = geometryTracker.geometry?.coordinates ?: emptyList(),
                    responseCoords = coordinatesResponse?.coordinates ?: emptyList()
                ),
                forceReplace = forceReplace
            )
        }
        val fallbackTracker = when (val result = trackRepository.getTracker(trackerId, forceRefresh = false)) {
            is RepositoryResult.Success -> result.data
            is RepositoryResult.Failure -> null
        }
            ?: trackRepository.getTrackerFromCache(trackerId)
            ?: Tracker(id = trackerId, name = SelectedTrackerPrefs.selectedTrackerName(context), color = null)
        return MapTrackSnapshot(
            tracker = fallbackTracker,
            coordinates = MapSingleTrackerLoadUtils.mergedCoordinates(
                geometryCoords = fallbackTracker.geometry?.coordinates ?: emptyList(),
                responseCoords = coordinatesResponse?.coordinates ?: emptyList()
            ),
            forceReplace = forceReplace
        )
    }
}
