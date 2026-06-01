package com.geovault.tracker.history

import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.presentation.TrackerMapTrailDecimationPolicy

object TrackerHistoryRenderMapper {
    fun toQueuedLocations(
        snapshot: TrackerHistorySnapshot?,
        trailPointLimit: Int,
    ): List<QueuedLocation> {
        if (snapshot == null) return emptyList()
        val raw = snapshot.points.map { it.toQueuedLocation() }
        val rendered = TrackerMapTrailDecimationPolicy.fitToCount(raw, trailPointLimit)
        TrackerHistoryDiagnostics.logRenderDecimation(
            trackerId = snapshot.key.normalizedTrackerId,
            window = snapshot.key.window.normalizedKey,
            rawCount = raw.size,
            renderedCount = rendered.size,
        )
        return rendered
    }

    fun trailsByTracker(
        snapshots: Map<TrackerHistoryKey, TrackerHistorySnapshot>,
        trackerIds: Collection<String>,
        window: TrackerHistoryWindow,
        trailPointLimit: Int,
    ): Map<String, List<QueuedLocation>> {
        return trackerIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .associateWith { trackerId ->
                val key = TrackerHistoryKey(trackerId, window)
                toQueuedLocations(snapshots[key], trailPointLimit)
            }
    }

    fun snapshotFor(
        snapshots: Map<TrackerHistoryKey, TrackerHistorySnapshot>,
        trackerId: String,
        window: TrackerHistoryWindow,
    ): TrackerHistorySnapshot? {
        return snapshots[TrackerHistoryKey(trackerId.trim(), window)]
    }
}
