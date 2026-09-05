package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.services.TrackingRuntimeSnapshot

/**
 * Draw-time trail: history-mapped trunk plus unpublished live overlay, a newer remote head,
 * and the local runtime head for the recorded tracker. Compose deferral must not freeze this.
 */
object TrackerMapLiveDrawMerge {
    fun mergeSingle(
        mappedTrail: List<QueuedLocation>,
        unpublishedOverlay: List<QueuedLocation>,
        remoteLastPoint: TrackPointEvent?,
        runtime: TrackingRuntimeSnapshot,
        displayedTrackerId: String,
        trailPointLimit: Int,
    ): List<QueuedLocation> {
        val trackerId = displayedTrackerId.trim().ifBlank { runtime.selectedTrackerId.trim() }
        val withOverlay = appendUnpublishedOverlay(mappedTrail, unpublishedOverlay)
        val withRemote = appendRemoteHeadIfNewer(withOverlay, trackerId, remoteLastPoint)
        return TrackerMapEffectiveSessionProjector.singleTrailWithLocalRuntimeOverlay(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            runtime = runtime,
            displayedTrackerId = displayedTrackerId,
            trail = withRemote,
            trailPointLimit = trailPointLimit,
        )
    }

    fun mergeMulti(
        mappedTrails: Map<String, List<QueuedLocation>>,
        unpublishedOverlaysByTracker: Map<String, List<QueuedLocation>>,
        remoteLastPoints: Map<String, TrackPointEvent>,
        runtime: TrackingRuntimeSnapshot,
        mode: TrackerMapDisplayMode,
        groupTrackerIds: Set<String>,
        trailPointLimit: Int,
    ): Map<String, List<QueuedLocation>> {
        val trackerIds = mappedTrails.keys + unpublishedOverlaysByTracker.keys + remoteLastPoints.keys
        val merged = trackerIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .associateWith { trackerId ->
                val withOverlay = appendUnpublishedOverlay(
                    mappedTrails[trackerId].orEmpty(),
                    unpublishedOverlaysByTracker[trackerId].orEmpty(),
                )
                appendRemoteHeadIfNewer(withOverlay, trackerId, remoteLastPoints[trackerId])
            }
        return TrackerMapEffectiveSessionProjector.allQueueTrailsWithLocalRuntimeOverlay(
            mode = mode,
            runtime = runtime,
            groupTrackerIds = groupTrackerIds,
            allQueueTrailsByTracker = merged,
            trailPointLimit = trailPointLimit,
        )
    }

    fun appendUnpublishedOverlay(
        mappedTrail: List<QueuedLocation>,
        unpublishedOverlay: List<QueuedLocation>,
    ): List<QueuedLocation> {
        if (unpublishedOverlay.isEmpty()) return mappedTrail
        val existing = mappedTrail.map { pointKey(it) }.toSet()
        val extra = unpublishedOverlay.filter { pointKey(it) !in existing }
        if (extra.isEmpty()) return mappedTrail
        return (mappedTrail + extra).sortedBy { it.time }
    }

    fun appendRemoteHeadIfNewer(
        trail: List<QueuedLocation>,
        trackerId: String,
        remoteLastPoint: TrackPointEvent?,
    ): List<QueuedLocation> {
        if (remoteLastPoint == null) return trail
        val normalizedId = trackerId.trim()
        if (normalizedId.isEmpty()) return trail
        val last = trail.lastOrNull()
        if (last != null && last.time >= remoteLastPoint.timestampMs) return trail
        return trail + remoteLastPoint.toQueuedLocation(normalizedId)
    }

    private fun pointKey(point: QueuedLocation): String {
        return "${point.trackerId.trim()}|${point.time}|${point.latitude}|${point.longitude}"
    }

    private fun TrackPointEvent.toQueuedLocation(trackerId: String): QueuedLocation {
        return QueuedLocation(
            id = 0L,
            trackerId = trackerId,
            time = timestampMs,
            latitude = lat,
            longitude = lon,
            altitude = null,
            speed = gpsSpeedMps,
            bearing = gpsBearingDeg,
            accuracy = accuracyMeters,
            sat = null,
            prov = TrackerMapPointProvenancePolicy.PROVENANCE_REMOTE_STREAM,
            dist = null,
            startTimestampMs = null,
        )
    }
}
