package com.geovault.tracker.presentation

object TrackerMapCoordinateMergePolicy {
    fun mergedCoordinates(
        geometryCoords: List<List<Double>>,
        responseCoords: List<List<Double>>
    ): List<List<Double>> {
        val normalizedGeometry = normalizeRawCoordinates(geometryCoords).toMutableList()
        val normalizedResponse = normalizeRawCoordinates(responseCoords).toMutableList()
        if (normalizedGeometry.isEmpty()) return normalizedResponse
        if (normalizedResponse.isEmpty()) return normalizedGeometry
        val geometryLatestTs = latestTimestampMs(normalizedGeometry)
        val responseLatestTs = latestTimestampMs(normalizedResponse)
        val base = when {
            geometryLatestTs != null && responseLatestTs != null -> {
                if (geometryLatestTs >= responseLatestTs) normalizedGeometry else normalizedResponse
            }
            responseLatestTs != null -> normalizedResponse
            geometryLatestTs != null -> normalizedGeometry
            else -> normalizedResponse
        }
        val other = if (base === normalizedGeometry) normalizedResponse else normalizedGeometry
        mergeNewerPointsInto(base, other)
        return base
    }

    private fun normalizeRawCoordinates(rawCoords: List<List<Double>>): List<List<Double>> {
        val normalized = mutableListOf<List<Double>>()
        rawCoords.forEach { coord ->
            if (coord.size < 2) return@forEach
            val lon = coord[0]
            val lat = coord[1]
            val rawTs = coord.getOrNull(2)?.toLong() ?: 0L
            val tsMs = TrackerMapSessionWindowPolicy.normalizeTimestampToMs(rawTs) ?: 0L
            normalized += listOf(lon, lat, tsMs.toDouble())
        }
        return normalized
    }

    private fun latestTimestampMs(coords: List<List<Double>>): Long? {
        return coords.asReversed()
            .asSequence()
            .mapNotNull { coord -> coord.getOrNull(2)?.toLong()?.takeIf { it > 0L } }
            .firstOrNull()
    }

    private fun mergeNewerPointsInto(target: MutableList<List<Double>>, source: List<List<Double>>) {
        source.forEach { coord ->
            if (coord.size < 2) return@forEach
            val lon = coord[0]
            val lat = coord[1]
            val ts = coord.getOrNull(2)?.toLong() ?: 0L
            appendPointIfNewer(target, lon, lat, ts)
        }
    }

    private fun appendPointIfNewer(
        coords: MutableList<List<Double>>,
        lon: Double,
        lat: Double,
        timestampMs: Long
    ) {
        val normalizedTimestampMs = TrackerMapSessionWindowPolicy.normalizeTimestampToMs(timestampMs) ?: 0L
        val last = coords.lastOrNull()
        if (last != null) {
            val lastTs = last.getOrNull(2)?.toLong() ?: 0L
            if (lastTs > 0L && normalizedTimestampMs > 0L && normalizedTimestampMs < lastTs) return
            val lastLon = last.getOrNull(0)
            val lastLat = last.getOrNull(1)
            if (lastLon != null && lastLat != null &&
                kotlin.math.abs(lastLon - lon) < 1e-9 &&
                kotlin.math.abs(lastLat - lat) < 1e-9 &&
                normalizedTimestampMs == lastTs
            ) {
                return
            }
        }
        coords += listOf(lon, lat, normalizedTimestampMs.toDouble())
    }
}
