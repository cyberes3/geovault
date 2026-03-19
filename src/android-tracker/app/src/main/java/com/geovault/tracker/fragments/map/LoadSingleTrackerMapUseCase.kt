package com.geovault.tracker.fragments.map

import android.content.Context
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.SelectedTrackerPrefs
import com.geovault.tracker.Tracker

class LoadSingleTrackerMapUseCase(
    private val trackRepository: MapTrackRepository
) {
    suspend fun execute(
        context: Context,
        trackerId: String?,
        displayedTrackerId: String?,
        forceReplace: Boolean
    ): MapTrackSnapshot? {
        val selectedTrackerId = SelectedTrackerPrefs.selectedTrackerId(context)
        val resolvedId = trackerId ?: MapDataLoader.resolveActiveSingleTrackerId(
            trackingRunning = false,
            displayedTrackerId = displayedTrackerId,
            selectedTrackerId = selectedTrackerId
        )
        if (resolvedId.isBlank()) return null

        val geometryTracker = when (val result = trackRepository.getTrackerGeometry(resolvedId, allData = true)) {
            is RepositoryResult.Success -> result.data
            is RepositoryResult.Failure -> null
        }
        if (geometryTracker != null) {
            return MapTrackSnapshot(
                tracker = geometryTracker,
                coordinates = geometryTracker.geometry?.coordinates ?: emptyList(),
                forceReplace = forceReplace
            )
        }

        val coordinatesResponse = when (
            val result = trackRepository.getTrackerCoordinates(resolvedId, allData = true)
        ) {
            is RepositoryResult.Success -> result.data
            is RepositoryResult.Failure -> null
        }
        val fallbackTracker = when (val result = trackRepository.getTracker(resolvedId, forceRefresh = false)) {
            is RepositoryResult.Success -> result.data
            is RepositoryResult.Failure -> null
        }
            ?: trackRepository.getTrackerFromCache(resolvedId)
            ?: Tracker(id = resolvedId, name = SelectedTrackerPrefs.selectedTrackerName(context), color = null)
        return MapTrackSnapshot(
            tracker = fallbackTracker,
            coordinates = coordinatesResponse?.coordinates ?: emptyList(),
            forceReplace = forceReplace
        )
    }
}

