package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation

object TrackerMapTrailMergePolicy {
    /**
     * Merge a server-fetched trail with the in-memory live overlay (local GPS / remote stream).
     *
     * SESSION SAFETY: when [activeSessionStartMs] is non-null, overlay candidates whose
     * `startTimestampMs` is also non-null but does not match are dropped. This prevents
     * stale local-queue points from a previous, never-uploaded session from grafting onto
     * the active session's trail and producing the "spike" the user reports (which only
     * disappears after restart, because restart wipes the local queue). Points with
     * a null `startTimestampMs` retain the prior behavior so historical data without
     * provenance metadata is not silently dropped.
     */
    fun mergeServerTrailWithLiveOverlay(
        serverTrail: List<QueuedLocation>,
        currentTrail: List<QueuedLocation>,
        allowedLiveOverlayTrackerIds: Set<String>,
        trailPointLimit: Int,
        activeSessionStartMs: Long? = null,
    ): List<QueuedLocation> {
        val allowedOverlayIds = normalizedIds(allowedLiveOverlayTrackerIds)
        if (allowedOverlayIds.isEmpty()) return serverTrail
        val latestServerTime = serverTrail.maxOfOrNull { it.time }
        val overlayCandidates = currentTrail
            .filter(TrackerMapPointProvenancePolicy::isLiveOverlay)
            .filter { it.trackerId.trim() in allowedOverlayIds }
        val timeFilteredOverlay = overlayCandidates
            .filter { latestServerTime == null || it.time > latestServerTime }
        val liveBuffer = timeFilteredOverlay
            .filter { point ->
                activeSessionStartMs == null ||
                    point.startTimestampMs == null ||
                    point.startTimestampMs == activeSessionStartMs
            }
        if (liveBuffer.isEmpty()) return serverTrail
        val merged = (serverTrail.filterNot(TrackerMapPointProvenancePolicy::isLiveOverlay) + liveBuffer)
            .sortedBy { it.time }
        return TrackerMapTrailDecimationPolicy.fitToCount(merged, trailPointLimit)
    }

    /**
     * Multi-tracker variant. The [activeSessionStartByTracker] map carries the active
     * recording session start time for each *locally recording* tracker (typically a
     * single entry — the locally-recorded tracker). Trackers absent from the map have no
     * known session and are merged with no session filter, since we cannot infer remote
     * session boundaries reliably from server-streamed points alone.
     *
     * [extraLiveOverlaysByTracker] carries freshly-loaded local-queue rows for the
     * locally-recorded tracker (typically one entry). They are spliced into the per-id
     * `currentTrail` input as additional live-overlay candidates, mirroring how the
     * SINGLE_SERVER caller threads `singleQueueOverlay` through `mergeServerTrailWithLiveOverlay`.
     * Critically, [serverTrails] is forwarded verbatim — queue rows never replace server
     * geometry for the locally-recorded tracker, so multi mode preserves real history for
     * every member.
     */
    fun mergeServerTrailsWithLiveOverlays(
        serverTrails: Map<String, List<QueuedLocation>>,
        currentTrails: Map<String, List<QueuedLocation>>,
        allowedLiveOverlayTrackerIds: Set<String>,
        trailPointLimit: Int,
        activeSessionStartByTracker: Map<String, Long> = emptyMap(),
        extraLiveOverlaysByTracker: Map<String, List<QueuedLocation>> = emptyMap(),
    ): Map<String, List<QueuedLocation>> {
        if (currentTrails.isEmpty() && extraLiveOverlaysByTracker.isEmpty()) return serverTrails
        val allowedOverlayIds = normalizedIds(allowedLiveOverlayTrackerIds)
        val normalizedExtras = extraLiveOverlaysByTracker
            .mapKeys { it.key.trim() }
            .filterKeys { it.isNotEmpty() }
        val trackerIds = serverTrails.keys +
            (currentTrails.keys intersect allowedOverlayIds) +
            (normalizedExtras.keys intersect allowedOverlayIds)
        return trackerIds.associateWith { trackerId ->
            val normalizedId = trackerId.trim()
            val current = currentTrails[trackerId].orEmpty()
            val extra = normalizedExtras[normalizedId].orEmpty()
            val combinedCurrent = if (extra.isEmpty()) current else current + extra
            mergeServerTrailWithLiveOverlay(
                serverTrail = serverTrails[trackerId].orEmpty(),
                currentTrail = combinedCurrent,
                allowedLiveOverlayTrackerIds = setOf(trackerId),
                trailPointLimit = trailPointLimit,
                activeSessionStartMs = activeSessionStartByTracker[normalizedId],
            )
        }
    }

    private fun normalizedIds(ids: Set<String>): Set<String> {
        return ids.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
}
