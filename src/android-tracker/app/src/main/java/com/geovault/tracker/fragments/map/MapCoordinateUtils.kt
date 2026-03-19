package com.geovault.tracker.fragments.map

import com.geovault.tracker.TrackUpdateHelper
import com.geovault.tracker.pipeline.CanonicalTimeNormalizer
import kotlin.math.abs

internal object MapCoordinateUtils {
    fun normalizeRawCoordinates(rawCoords: List<List<Double>>): MutableList<List<Double>> {
        val normalized = mutableListOf<List<Double>>()
        for (coord in rawCoords) {
            if (coord.size < 2) continue
            val lon = (coord[0] as? Number)?.toDouble() ?: continue
            val lat = (coord[1] as? Number)?.toDouble() ?: continue
            val tsRaw = (coord.getOrNull(2) as? Number)?.toDouble() ?: 0.0
            val tsMs = CanonicalTimeNormalizer.normalizeTimestampMs(tsRaw.toLong(), System.currentTimeMillis()).toDouble()
            normalized.add(listOf(lon, lat, tsMs))
        }
        return normalized.takeLast(TrackUpdateHelper.MAX_POINTS).toMutableList()
    }

    fun normalizeTimestampToMs(timestamp: Long): Long {
        return CanonicalTimeNormalizer.normalizeTimestampMs(timestamp, System.currentTimeMillis())
    }

    fun timestampFromCoordinateMs(coord: List<*>, fallbackMs: Long? = null): Long? {
        val rawTs = (coord.getOrNull(2) as? Number)?.toLong() ?: return fallbackMs
        return normalizeTimestampToMs(rawTs)
    }

    fun appendStreamedPointIfNewer(
        coords: MutableList<List<Double>>,
        lon: Double,
        lat: Double,
        timestampMs: Long
    ): Boolean {
        val normalizedTimestampMs = normalizeTimestampToMs(timestampMs)
        val last = coords.lastOrNull()
        if (last != null) {
            val lastTs = (last.getOrNull(2) as? Number)?.toLong() ?: 0L
            if (lastTs > 0L && normalizedTimestampMs > 0L && normalizedTimestampMs < lastTs) return false
            val lastLon = (last.getOrNull(0) as? Number)?.toDouble()
            val lastLat = (last.getOrNull(1) as? Number)?.toDouble()
            if (lastLon != null &&
                lastLat != null &&
                abs(lastLon - lon) < 1e-9 &&
                abs(lastLat - lat) < 1e-9 &&
                normalizedTimestampMs == lastTs
            ) {
                return false
            }
        }
        coords.add(listOf(lon, lat, normalizedTimestampMs.toDouble()))
        while (coords.size > TrackUpdateHelper.MAX_POINTS) {
            coords.removeAt(0)
        }
        return true
    }

    fun mergeNewerPointsInto(target: MutableList<List<Double>>, source: List<List<Double>>) {
        for (coord in source) {
            if (coord.size < 2) continue
            val lon = (coord.getOrNull(0) as? Number)?.toDouble() ?: continue
            val lat = (coord.getOrNull(1) as? Number)?.toDouble() ?: continue
            val ts = (coord.getOrNull(2) as? Number)?.toLong() ?: 0L
            appendStreamedPointIfNewer(target, lon, lat, ts)
        }
    }
}
