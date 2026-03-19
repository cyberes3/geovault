package com.geovault.tracker.fragments.map

import android.content.Context
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.SelectedTrackerPrefs
import com.geovault.tracker.Tracker

class LoadSingleTrackerMapUseCase(
    private val trackRepository: MapTrackRepository
) {
    private fun sanitizeHistory(coords: MutableList<List<Double>>): List<List<Double>> {
        return if (coords.size >= 2) coords else emptyList()
    }

    private fun mergedCoordinates(
        geometryCoords: List<List<Double>>,
        responseCoords: List<List<Double>>
    ): List<List<Double>> {
        val normalizedGeometry = MapCoordinateUtils.normalizeRawCoordinates(geometryCoords)
        val normalizedResponse = MapCoordinateUtils.normalizeRawCoordinates(responseCoords)
        if (normalizedGeometry.isEmpty()) return sanitizeHistory(normalizedResponse)
        if (normalizedResponse.isEmpty()) return sanitizeHistory(normalizedGeometry)
        val base = if (normalizedGeometry.size >= normalizedResponse.size) {
            normalizedGeometry
        } else {
            normalizedResponse
        }
        val other = if (base === normalizedGeometry) normalizedResponse else normalizedGeometry
        MapCoordinateUtils.mergeNewerPointsInto(base, other)
        // Single-point history baselines cause reset/jump artifacts on resume.
        // Single-tracker rendering should bootstrap from a real path (>=2 points) or live events.
        return sanitizeHistory(base)
    }

    suspend fun execute(
        context: Context,
        trackerId: String?,
        displayedTrackerId: String?,
        forceReplace: Boolean,
        coordinatesOnly: Boolean = false
    ): MapTrackSnapshot? {
        val selectedTrackerId = SelectedTrackerPrefs.selectedTrackerId(context)
        val resolvedId = trackerId ?: MapDataLoader.resolveActiveSingleTrackerId(
            trackingRunning = false,
            displayedTrackerId = displayedTrackerId,
            selectedTrackerId = selectedTrackerId
        )
        if (resolvedId.isBlank()) return null

        val coordinatesResponse = when (
            val result = trackRepository.getTrackerCoordinates(resolvedId, allData = true)
        ) {
            is RepositoryResult.Success -> result.data
            is RepositoryResult.Failure -> null
        }
        if (coordinatesOnly) {
            val tracker = when (val result = trackRepository.getTracker(resolvedId, forceRefresh = false)) {
                is RepositoryResult.Success -> result.data
                is RepositoryResult.Failure -> null
            }
                ?: trackRepository.getTrackerFromCache(resolvedId)
                ?: Tracker(id = resolvedId, name = SelectedTrackerPrefs.selectedTrackerName(context), color = null)
            return MapTrackSnapshot(
                tracker = tracker,
                coordinates = mergedCoordinates(
                    geometryCoords = emptyList(),
                    responseCoords = coordinatesResponse?.coordinates ?: emptyList()
                ),
                forceReplace = forceReplace
            )
        }
        val geometryTracker = when (val result = trackRepository.getTrackerGeometry(resolvedId, allData = true)) {
            is RepositoryResult.Success -> result.data
            is RepositoryResult.Failure -> null
        }
        if (geometryTracker != null) {
            return MapTrackSnapshot(
                tracker = geometryTracker,
                coordinates = mergedCoordinates(
                    geometryCoords = geometryTracker.geometry?.coordinates ?: emptyList(),
                    responseCoords = coordinatesResponse?.coordinates ?: emptyList()
                ),
                forceReplace = forceReplace
            )
        }
        val fallbackTracker = when (val result = trackRepository.getTracker(resolvedId, forceRefresh = false)) {
            is RepositoryResult.Success -> result.data
            is RepositoryResult.Failure -> null
        }
            ?: trackRepository.getTrackerFromCache(resolvedId)
            ?: Tracker(id = resolvedId, name = SelectedTrackerPrefs.selectedTrackerName(context), color = null)
        return MapTrackSnapshot(
            tracker = fallbackTracker,
            coordinates = mergedCoordinates(
                geometryCoords = fallbackTracker.geometry?.coordinates ?: emptyList(),
                responseCoords = coordinatesResponse?.coordinates ?: emptyList()
            ),
            forceReplace = forceReplace
        )
    }
}

