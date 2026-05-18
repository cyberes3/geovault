package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation

/**
 * Commits trail data after server reloads. Prevents empty server trunks from wiping trails that
 * still have a valid client-side projection or local queue overlay.
 */
internal object TrackerMapTrailCommitPolicy {

    fun resolveSingleTrail(
        reloadReason: TrackerMapTrailReloadReason,
        serverMergedTrail: List<QueuedLocation>,
        preReloadFilteredTrail: List<QueuedLocation>,
    ): List<QueuedLocation> {
        if (shouldPreserveTrailOnEmptyMerge(reloadReason) &&
            serverMergedTrail.isEmpty() &&
            preReloadFilteredTrail.isNotEmpty()
        ) {
            return preReloadFilteredTrail
        }
        return serverMergedTrail
    }

    fun resolveMultiTrails(
        reloadReason: TrackerMapTrailReloadReason,
        serverMergedTrails: Map<String, List<QueuedLocation>>,
        preReloadFilteredTrails: Map<String, List<QueuedLocation>>,
        refreshedTrackerIds: Set<String>,
    ): Map<String, List<QueuedLocation>> {
        if (!shouldPreserveTrailOnEmptyMerge(reloadReason) || refreshedTrackerIds.isEmpty()) {
            return serverMergedTrails
        }
        val normalizedRefreshIds = refreshedTrackerIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (normalizedRefreshIds.isEmpty()) return serverMergedTrails
        val merged = serverMergedTrails.toMutableMap()
        for (trackerId in normalizedRefreshIds) {
            val serverTrail = merged[trackerId].orEmpty()
            if (serverTrail.isNotEmpty()) continue
            val fallback = preReloadFilteredTrails[trackerId].orEmpty()
            if (fallback.isNotEmpty()) {
                merged[trackerId] = fallback
            }
        }
        return merged
    }

    suspend fun expandQueueOverlays(
        reloadReason: TrackerMapTrailReloadReason,
        overlayTrackerIds: Set<String>,
        loadedOverlays: Map<String, List<QueuedLocation>>,
        loadQueue: suspend (String) -> List<QueuedLocation>,
    ): Map<String, List<QueuedLocation>> {
        if (reloadReason != TrackerMapTrailReloadReason.RecentDataWindowChange) {
            return loadedOverlays
        }
        val normalizedIds = overlayTrackerIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (normalizedIds.isEmpty()) return loadedOverlays
        val expanded = loadedOverlays.toMutableMap()
        for (trackerId in normalizedIds) {
            if (expanded[trackerId]?.isNotEmpty() == true) continue
            val queueTrail = loadQueue(trackerId)
            if (queueTrail.isNotEmpty()) {
                expanded[trackerId] = queueTrail
            }
        }
        return expanded
    }

    fun shouldCapturePreReloadSnapshot(reason: TrackerMapTrailReloadReason): Boolean {
        return reason.allowServerHistoryFetch && reason != TrackerMapTrailReloadReason.MapContextChange
    }

    private fun shouldPreserveTrailOnEmptyMerge(reason: TrackerMapTrailReloadReason): Boolean {
        return when (reason) {
            TrackerMapTrailReloadReason.RecentDataWindowChange,
            TrackerMapTrailReloadReason.ExplicitTrackerLoad,
            TrackerMapTrailReloadReason.StreamingStart,
            TrackerMapTrailReloadReason.RestoreSelectedAfterStreaming -> true
            TrackerMapTrailReloadReason.MapContextChange,
            TrackerMapTrailReloadReason.GenericMapRefresh,
            TrackerMapTrailReloadReason.MetadataMapRefresh -> false
        }
    }
}
