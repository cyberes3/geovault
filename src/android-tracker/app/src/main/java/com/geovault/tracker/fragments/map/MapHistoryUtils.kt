package com.geovault.tracker.fragments.map

import com.geovault.tracker.TrackUpdateHelper
import org.maplibre.android.geometry.LatLng

internal object MapHistoryUtils {
    fun applyCoordinatesPreview(
        coordinates: List<List<Double>>,
        forceReplace: Boolean,
        trackPoints: MutableList<LatLng>,
        trackTimestamps: MutableList<Long>
    ): Boolean {
        if (coordinates.isEmpty()) return false
        val fallbackBaseMs = System.currentTimeMillis()
        val normalized = coordinates.takeLast(TrackUpdateHelper.MAX_POINTS)
            .mapIndexedNotNull { index, coord ->
                if (coord.size < 2) return@mapIndexedNotNull null
                val latLng = LatLng(coord[1], coord[0])
                val timestampMs = MapCoordinateUtils.timestampFromCoordinateMs(coord, fallbackBaseMs + index)
                    ?: (fallbackBaseMs + index)
                latLng to timestampMs
            }
        if (normalized.isEmpty()) return false

        if (forceReplace || trackPoints.isEmpty()) {
            trackPoints.clear()
            trackTimestamps.clear()
            trackPoints.addAll(normalized.map { it.first })
            trackTimestamps.addAll(normalized.map { it.second })
        } else {
            normalized.forEach { (latLng, timestampMs) ->
                TrackUpdateHelper.updateTrack(trackPoints, trackTimestamps, latLng, timestampMs)
            }
        }
        return true
    }

    fun applyGeometryToTrack(
        normalizedCoords: List<List<Double>>,
        mergeExternalStreaming: Boolean,
        trackPoints: MutableList<LatLng>,
        trackTimestamps: MutableList<Long>
    ) {
        val lastCoords = normalizedCoords.takeLast(TrackUpdateHelper.MAX_POINTS)
        if (mergeExternalStreaming) {
            val geomLatestTs = lastCoords.lastOrNull()
                ?.let { MapCoordinateUtils.timestampFromCoordinateMs(it) }
                ?: Long.MAX_VALUE
            val streamedAfterGeom = trackPoints.zip(trackTimestamps)
                .filter { it.second > geomLatestTs }
            trackPoints.clear()
            trackTimestamps.clear()
            trackPoints.addAll(lastCoords.map { LatLng(it[1], it[0]) })
            trackTimestamps.addAll(
                lastCoords.mapIndexed { index, coord ->
                    MapCoordinateUtils.timestampFromCoordinateMs(coord, geomLatestTs + index)
                        ?: (geomLatestTs + index)
                }
            )
            for ((pt, ts) in streamedAfterGeom) {
                TrackUpdateHelper.updateTrack(trackPoints, trackTimestamps, pt, ts)
            }
            return
        }

        trackPoints.clear()
        trackTimestamps.clear()
        trackPoints.addAll(lastCoords.map { LatLng(it[1], it[0]) })
        val fallbackBase = System.currentTimeMillis()
        trackTimestamps.addAll(
            lastCoords.mapIndexed { index, coord ->
                MapCoordinateUtils.timestampFromCoordinateMs(coord, fallbackBase + index)
                    ?: (fallbackBase + index)
            }
        )
    }
}
