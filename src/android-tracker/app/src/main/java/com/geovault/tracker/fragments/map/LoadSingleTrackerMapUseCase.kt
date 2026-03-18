package com.geovault.tracker.fragments.map

import android.content.Context
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

        val geometryTracker = trackRepository.getTrackerGeometry(context, resolvedId)
        if (geometryTracker != null) {
            return MapTrackSnapshot(
                tracker = geometryTracker,
                coordinates = geometryTracker.geometry?.coordinates ?: emptyList(),
                forceReplace = forceReplace
            )
        }

        val coordinatesResponse = trackRepository.getTrackerCoordinates(context, resolvedId)
        val fallbackTracker = trackRepository.getTracker(context, resolvedId, forceRefresh = false)
            ?: trackRepository.getTrackerFromCache(resolvedId)
            ?: Tracker(id = resolvedId, name = SelectedTrackerPrefs.selectedTrackerName(context), color = null)
        return MapTrackSnapshot(
            tracker = fallbackTracker,
            coordinates = coordinatesResponse?.coordinates ?: emptyList(),
            forceReplace = forceReplace
        )
    }
}

