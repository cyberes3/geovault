package com.geovault.tracker.fragments.map

import com.geovault.tracker.TrackUpdateHelper
import org.maplibre.android.geometry.LatLng

internal object MapHistoryUtils {
    private fun parseValidLatLng(coord: List<Double>): LatLng? {
        if (coord.size < 2) return null
        val lon = coord[0]
        val lat = coord[1]
        if (!lat.isFinite() || !lon.isFinite()) return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return LatLng(lat, lon)
    }

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
                val latLng = parseValidLatLng(coord) ?: return@mapIndexedNotNull null
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
        val lastCoords = normalizedCoords
            .takeLast(TrackUpdateHelper.MAX_POINTS)
            .filter { parseValidLatLng(it) != null }
        if (mergeExternalStreaming) {
            if (lastCoords.isEmpty()) return
            val parsedGeomTs = lastCoords.map { MapCoordinateUtils.timestampFromCoordinateMs(it) }
            val hasGeometryTimestamps = parsedGeomTs.any { it != null }
            val geometryTimestamps = if (hasGeometryTimestamps) {
                val fallbackBase = parsedGeomTs.filterNotNull().maxOrNull() ?: System.currentTimeMillis()
                parsedGeomTs.mapIndexed { index, ts -> ts ?: (fallbackBase + index) }
            } else {
                // Geometry has no server timestamps (lon/lat only). Keep live-streamed points by
                // placing geometry in the past relative to already-streamed points.
                val streamBase = trackTimestamps.minOrNull() ?: System.currentTimeMillis()
                val fallbackStart = streamBase - lastCoords.size - 1L
                lastCoords.indices.map { idx -> fallbackStart + idx }
            }
            val geomLatestTs = geometryTimestamps.last()
            val streamedAfterGeom = trackPoints.zip(trackTimestamps)
                .filter { it.second > geomLatestTs }
            trackPoints.clear()
            trackTimestamps.clear()
            trackPoints.addAll(lastCoords.mapNotNull(::parseValidLatLng))
            trackTimestamps.addAll(geometryTimestamps)
            for ((pt, ts) in streamedAfterGeom) {
                TrackUpdateHelper.updateTrack(trackPoints, trackTimestamps, pt, ts)
            }
            return
        }

        trackPoints.clear()
        trackTimestamps.clear()
        trackPoints.addAll(lastCoords.mapNotNull(::parseValidLatLng))
        val fallbackBase = System.currentTimeMillis()
        trackTimestamps.addAll(
            lastCoords.mapIndexed { index, coord ->
                MapCoordinateUtils.timestampFromCoordinateMs(coord, fallbackBase + index)
                    ?: (fallbackBase + index)
            }
        )
    }
}
