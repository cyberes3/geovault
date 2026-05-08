package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.policy.WireTimestampNormalizer

/**
 * Build a renderable trail (`List<QueuedLocation>`) from server-shaped geometry +
 * point_params and apply the session-aware decimation cap.
 *
 * Extracted from `TrackerMapViewModel.mapCoordinatesToTrail` so the materialization
 * contract — specifically: "every preloaded point must carry its
 * `startTimestampMs` from `point_params[i].starttimestamp` so the
 * `recent_data_window` filter can attribute it to the correct session" — is
 * unit-testable without spinning up the full ViewModel.
 *
 * Regression context: a cached-cache preload path was passing `coordinates` but
 * dropping `point_params`, so every point ended up with `startTimestampMs = null`.
 * Under `recent_data_window=session`, the previous session then collapsed into
 * "no attribution" and disappeared from the map until the (slow) server fetch
 * arrived. Centralizing the materialization in this policy + a unit test makes
 * that class of bug a build-time regression instead of a silent display issue.
 */
object TrackerMapTrailMaterializationPolicy {

    fun materialize(
        trackerId: String,
        coordinates: List<List<Double>>,
        pointParams: List<Map<String, Any?>>? = null,
        existingTrailMinTimeMs: Long? = null,
        trailPointLimit: Int,
    ): List<QueuedLocation> {
        if (coordinates.isEmpty()) return emptyList()
        val normalizedTrackerId = trackerId.trim()
        if (normalizedTrackerId.isEmpty()) return emptyList()

        val latestAccuracyMeters = pointParams
            ?.lastOrNull()
            ?.get("acc")
            ?.let { raw ->
                when (raw) {
                    is Number -> raw.toFloat()
                    is String -> raw.toFloatOrNull()
                    else -> null
                }
            }
            ?.takeIf { it.isFinite() && it > 0f }

        val timestamps = resolveGeometryTimestamps(coordinates, existingTrailMinTimeMs)
        val materialized = coordinates.mapIndexedNotNull { index, point ->
            val lon = point.getOrNull(0) ?: return@mapIndexedNotNull null
            val lat = point.getOrNull(1) ?: return@mapIndexedNotNull null
            val startTimestampMs = pointParams?.getOrNull(index)?.let { params ->
                WireTimestampNormalizer.normalizeToMilliseconds(params["starttimestamp"])
            }
            QueuedLocation(
                id = -(index + 1L),
                trackerId = normalizedTrackerId,
                time = timestamps[index],
                latitude = lat,
                longitude = lon,
                altitude = null,
                speed = null,
                bearing = null,
                accuracy = if (index == coordinates.lastIndex) latestAccuracyMeters else null,
                sat = null,
                prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY,
                dist = null,
                startTimestampMs = startTimestampMs,
            )
        }
        return TrackerMapTrailDecimationPolicy.fitToCount(materialized, trailPointLimit)
    }

    private fun resolveGeometryTimestamps(
        coordinates: List<List<Double>>,
        existingTrailMinTimeMs: Long? = null,
    ): List<Long> {
        val parsed = coordinates.map { coord ->
            val raw = coord.getOrNull(2)?.toLong() ?: return@map null
            TrackerMapSessionWindowPolicy.normalizeTimestampToMs(raw)
        }
        val hasRealTimestamps = parsed.any { it != null }
        if (hasRealTimestamps) {
            val fallbackBase = parsed.filterNotNull().maxOrNull() ?: 0L
            return parsed.mapIndexed { index, ts -> ts ?: (fallbackBase + index + 1) }
        }
        val anchor = existingTrailMinTimeMs ?: System.currentTimeMillis()
        val fallbackStart = anchor - coordinates.size - 1L
        return coordinates.indices.map { idx -> (fallbackStart + idx).coerceAtLeast(0L) }
    }
}
