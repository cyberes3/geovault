package com.geovault.tracker.fragments.map

import com.geovault.tracker.Tracker

internal object MapStreamingDataHelper {
    /**
     * Builds base coordinates for a tracker in multi-context (all-trackers or group):
     * cache, lastRendered, geometry, or seed from last_point; merges newer points from cache when needed.
     */
    fun getTrackerBaseCoordsForMultiContext(
        tracker: Tracker,
        trackId: String,
        multiTrackCoordsCache: Map<String, MutableList<List<Double>>>,
        lastAllTrackersCoordsById: Map<String, List<List<Double>>>?,
        seedCoordsFromLastPoint: (Tracker) -> MutableList<List<Double>>
    ): MutableList<List<Double>> {
        val cached = MapCoordinateUtils.normalizeRawCoordinates(multiTrackCoordsCache[trackId] ?: emptyList())
        val lastRendered = MapCoordinateUtils.normalizeRawCoordinates(lastAllTrackersCoordsById?.get(trackId) ?: emptyList())
        val geometry = MapCoordinateUtils.normalizeRawCoordinates(tracker.geometry?.coordinates ?: emptyList())

        val historyBase = when {
            lastRendered.size >= geometry.size -> lastRendered
            else -> geometry
        }

        val base = when {
            cached.size >= historyBase.size && cached.isNotEmpty() -> cached
            historyBase.isNotEmpty() -> historyBase
            else -> seedCoordsFromLastPoint(tracker)
        }

        if (cached.isNotEmpty() && base !== cached) {
            MapCoordinateUtils.mergeNewerPointsInto(base, cached)
        }
        return base
    }

    /**
     * Returns a single-point coordinate list from [tracker.last_point] with timestamp from [trackerLastUpdateMs].
     */
    fun seedCoordsFromLastPoint(tracker: Tracker, trackerLastUpdateMs: (Tracker) -> Long?): MutableList<List<Double>> {
        val lp = tracker.last_point
        if (lp == null || lp.size < 2) return mutableListOf()
        val tsMs = trackerLastUpdateMs(tracker)?.toDouble() ?: 0.0
        return mutableListOf(listOf(lp[0], lp[1], tsMs))
    }
}
