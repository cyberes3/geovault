package com.geovault.tracker.presentation

import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.db.QueuedLocation

internal data class TrackerMapTrailCommitInput(
    val reason: TrackerMapTrailReloadReason,
    val plan: TrackerMapTrailReloadPlan,
    val loaded: TrackerMapTrailLoadResult,
    val latestState: TrackerMapUiState,
    val trailPointLimit: Int,
    val activeSessionStartByTracker: Map<String, Long> = emptyMap(),
    val clearedHistoryTrackerIds: Set<String> = emptySet(),
)

internal data class TrackerMapTrailCommitResult(
    val trail: List<QueuedLocation>,
    val multiTrails: Map<String, List<QueuedLocation>>,
)

internal object TrackerMapTrailCommitPolicy {
    private const val TAG = "TrackerMapTrailCommitPolicy"

    fun resolve(input: TrackerMapTrailCommitInput): TrackerMapTrailCommitResult {
        val clearedIds = normalizedIds(input.clearedHistoryTrackerIds)
        val sanitizedLoaded = input.loaded.withClearedHistoryBarrier(
            clearedTrackerIds = clearedIds,
            activeSessionStartByTracker = input.activeSessionStartByTracker,
        )
        val currentSingleTrail = input.latestState.trail.withClearedHistoryBarrierForTracker(
            trackerId = input.plan.activeTrackerId,
            clearedTrackerIds = clearedIds,
            activeSessionStartByTracker = input.activeSessionStartByTracker,
        )
        val currentMultiTrails = input.latestState.allQueueTrailsByTracker.withClearedHistoryBarrier(
            clearedTrackerIds = clearedIds,
            activeSessionStartByTracker = input.activeSessionStartByTracker,
        )
        val singleSessionStart = input.activeSessionStartByTracker[input.plan.activeTrackerId.trim()]
        val singleQueueOverlay = sanitizedLoaded.queueOverlaysByTracker[input.plan.activeTrackerId].orEmpty()
        val singleLiveOverlayInput = if (singleQueueOverlay.isEmpty()) {
            currentSingleTrail
        } else {
            currentSingleTrail + singleQueueOverlay
        }
        val mergedTrail = TrackerMapTrailMergePolicy.mergeServerTrailWithLiveOverlay(
            serverTrail = sanitizedLoaded.singleTrailSeed,
            currentTrail = singleLiveOverlayInput,
            allowedLiveOverlayTrackerIds = setOfNotBlank(input.plan.activeTrackerId),
            trailPointLimit = input.trailPointLimit,
            activeSessionStartMs = singleSessionStart,
        ).preserveDuringFilterRefresh(
            reason = input.reason,
            previous = input.latestState.trail,
            loadedServerTrail = sanitizedLoaded.singleTrailSeed,
            trackerId = input.plan.activeTrackerId,
            clearedHistoryTrackerIds = clearedIds,
        )
        val mergedMultiTrails = TrackerMapTrailMergePolicy.mergeServerTrailsWithLiveOverlays(
            serverTrails = sanitizedLoaded.serverTrails,
            currentTrails = currentMultiTrails,
            allowedLiveOverlayTrackerIds = input.plan.trackerIds + setOfNotBlank(input.plan.overlayTrackerId),
            trailPointLimit = input.trailPointLimit,
            activeSessionStartByTracker = input.activeSessionStartByTracker,
            extraLiveOverlaysByTracker = sanitizedLoaded.queueOverlaysByTracker,
        ).preserveMultiDuringFilterRefresh(
            reason = input.reason,
            previous = input.latestState.allQueueTrailsByTracker,
            loadedServerTrails = sanitizedLoaded.serverTrails,
            clearedHistoryTrackerIds = clearedIds,
        )
        GeoVaultCaptureLog.i(
            TAG,
            "map_update trail_commit reason=${input.reason} source=${input.plan.source} active=${input.plan.activeTrackerId} " +
                "loadedSingle=${input.loaded.singleTrailSeed.size} loadedServer=${input.loaded.serverTrails.mapValues { it.value.size }} " +
                "loadedOverlay=${input.loaded.queueOverlaysByTracker.mapValues { it.value.size }} " +
                "currentSingle=${input.latestState.trail.size} currentMulti=${input.latestState.allQueueTrailsByTracker.mapValues { it.value.size }} " +
                "resultSingle=${mergedTrail.size} resultMulti=${mergedMultiTrails.mapValues { it.value.size }} " +
                "cleared=${clearedIds.sorted()} authoritative=${input.loaded.authoritativeServerTrackerIds.sorted()}"
        )
        return TrackerMapTrailCommitResult(
            trail = mergedTrail,
            multiTrails = mergedMultiTrails,
        )
    }

    private fun TrackerMapTrailLoadResult.withClearedHistoryBarrier(
        clearedTrackerIds: Set<String>,
        activeSessionStartByTracker: Map<String, Long>,
    ): TrackerMapTrailLoadResult {
        if (clearedTrackerIds.isEmpty()) return this
        val singleTrackerId = singleTrailSeed.firstOrNull()?.trackerId?.trim()
        val singleTrail = if (singleTrackerId != null && singleTrackerId in clearedTrackerIds) {
            emptyList()
        } else {
            singleTrailSeed
        }
        val server = serverTrails.filterKeys { it.trim() !in clearedTrackerIds }
        val overlays = queueOverlaysByTracker.mapValues { (trackerId, points) ->
            val normalizedId = trackerId.trim()
            if (normalizedId !in clearedTrackerIds) {
                points
            } else {
                val activeStart = activeSessionStartByTracker[normalizedId]
                points.filter { activeStart != null && it.startTimestampMs == activeStart }
            }
        }.filterValues { it.isNotEmpty() }
        return copy(
            serverTrails = server,
            queueOverlaysByTracker = overlays,
            singleTrailSeed = singleTrail,
        )
    }

    private fun List<QueuedLocation>.withClearedHistoryBarrierForTracker(
        trackerId: String,
        clearedTrackerIds: Set<String>,
        activeSessionStartByTracker: Map<String, Long>,
    ): List<QueuedLocation> {
        val normalizedId = trackerId.trim()
        if (normalizedId.isEmpty() || normalizedId !in clearedTrackerIds) return this
        val activeStart = activeSessionStartByTracker[normalizedId] ?: return emptyList()
        return filter { point ->
            point.trackerId.trim() == normalizedId &&
                point.startTimestampMs == activeStart &&
                TrackerMapPointProvenancePolicy.isLiveOverlay(point)
        }
    }

    private fun Map<String, List<QueuedLocation>>.withClearedHistoryBarrier(
        clearedTrackerIds: Set<String>,
        activeSessionStartByTracker: Map<String, Long>,
    ): Map<String, List<QueuedLocation>> {
        if (clearedTrackerIds.isEmpty()) return this
        return mapNotNull { (trackerId, points) ->
            val normalizedId = trackerId.trim()
            if (normalizedId !in clearedTrackerIds) {
                trackerId to points
            } else {
                val activeStart = activeSessionStartByTracker[normalizedId]
                val activeOnly = points.filter { point ->
                    activeStart != null &&
                        point.startTimestampMs == activeStart &&
                        TrackerMapPointProvenancePolicy.isLiveOverlay(point)
                }
                if (activeOnly.isEmpty()) null else trackerId to activeOnly
            }
        }.toMap()
    }

    private fun List<QueuedLocation>.preserveDuringFilterRefresh(
        reason: TrackerMapTrailReloadReason,
        previous: List<QueuedLocation>,
        loadedServerTrail: List<QueuedLocation>,
        trackerId: String,
        clearedHistoryTrackerIds: Set<String>,
    ): List<QueuedLocation> {
        val normalizedId = trackerId.trim()
        if (normalizedId.isNotEmpty() && normalizedId in clearedHistoryTrackerIds) return this
        if (reason != TrackerMapTrailReloadReason.RecentDataWindowChanged) return this
        if (isNotEmpty() || loadedServerTrail.isNotEmpty() || previous.isEmpty()) return this
        GeoVaultCaptureLog.w(
            TAG,
            "map_update trail_commit_preserve_single reason=$reason tracker=$normalizedId previous=${previous.size}"
        )
        return previous
    }

    private fun Map<String, List<QueuedLocation>>.preserveMultiDuringFilterRefresh(
        reason: TrackerMapTrailReloadReason,
        previous: Map<String, List<QueuedLocation>>,
        loadedServerTrails: Map<String, List<QueuedLocation>>,
        clearedHistoryTrackerIds: Set<String>,
    ): Map<String, List<QueuedLocation>> {
        if (reason != TrackerMapTrailReloadReason.RecentDataWindowChanged) return this
        if (previous.isEmpty()) return this
        val merged = toMutableMap()
        previous.forEach { (trackerId, previousTrail) ->
            val normalizedId = trackerId.trim()
            if (normalizedId.isEmpty() || normalizedId in clearedHistoryTrackerIds) return@forEach
            val loaded = loadedServerTrails[trackerId].orEmpty()
            if (previousTrail.isNotEmpty() && loaded.isEmpty() && merged[trackerId].orEmpty().isEmpty()) {
                GeoVaultCaptureLog.w(
                    TAG,
                    "map_update trail_commit_preserve_multi reason=$reason tracker=$normalizedId previous=${previousTrail.size}"
                )
                merged[trackerId] = previousTrail
            }
        }
        return merged
    }

    private fun normalizedIds(ids: Set<String>): Set<String> {
        return ids.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    private fun setOfNotBlank(value: String?): Set<String> {
        val normalized = value?.trim().orEmpty()
        return normalized.takeIf { it.isNotEmpty() }?.let(::setOf).orEmpty()
    }
}
