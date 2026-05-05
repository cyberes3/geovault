package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation

object TrackerMapTrailMergePolicy {
    fun mergeServerTrailWithLiveOverlay(
        serverTrail: List<QueuedLocation>,
        currentTrail: List<QueuedLocation>,
        allowedLiveOverlayTrackerIds: Set<String>,
        trailPointLimit: Int,
    ): List<QueuedLocation> {
        val allowedOverlayIds = normalizedIds(allowedLiveOverlayTrackerIds)
        if (allowedOverlayIds.isEmpty()) return serverTrail
        val latestServerTime = serverTrail.maxOfOrNull { it.time }
        val liveBuffer = currentTrail
            .filter(TrackerMapPointProvenancePolicy::isLiveOverlay)
            .filter { it.trackerId.trim() in allowedOverlayIds }
            .filter { latestServerTime == null || it.time > latestServerTime }
        if (liveBuffer.isEmpty()) return serverTrail
        return (serverTrail.filterNot(TrackerMapPointProvenancePolicy::isLiveOverlay) + liveBuffer)
            .sortedBy { it.time }
            .takeLast(trailPointLimit)
    }

    fun mergeServerTrailsWithLiveOverlays(
        serverTrails: Map<String, List<QueuedLocation>>,
        currentTrails: Map<String, List<QueuedLocation>>,
        allowedLiveOverlayTrackerIds: Set<String>,
        trailPointLimit: Int,
    ): Map<String, List<QueuedLocation>> {
        if (currentTrails.isEmpty()) return serverTrails
        val allowedOverlayIds = normalizedIds(allowedLiveOverlayTrackerIds)
        val trackerIds = serverTrails.keys + (currentTrails.keys intersect allowedOverlayIds)
        return trackerIds.associateWith { trackerId ->
            mergeServerTrailWithLiveOverlay(
                serverTrail = serverTrails[trackerId].orEmpty(),
                currentTrail = currentTrails[trackerId].orEmpty(),
                allowedLiveOverlayTrackerIds = setOf(trackerId),
                trailPointLimit = trailPointLimit,
            )
        }
    }

    private fun normalizedIds(ids: Set<String>): Set<String> {
        return ids.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
}
