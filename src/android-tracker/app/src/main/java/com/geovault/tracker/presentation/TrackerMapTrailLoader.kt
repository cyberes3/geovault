package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation

/**
 * Result of a single trail-reload IO pass. Server-fetched geometry is kept strictly separate
 * from local-queue overlays so the downstream merge cannot accidentally substitute one for
 * the other.
 *
 * - [serverTrails] is keyed by tracker id and only populated for `MULTI_SERVER` reloads. Each
 *   list carries `PROVENANCE_SERVER_GEOMETRY` rows that the merge treats as the trunk.
 * - [queueOverlaysByTracker] is keyed by tracker id and only populated for the locally-recorded
 *   tracker (when one applies). Rows carry `PROVENANCE_LOCAL_GPS`; the merge consumes them as
 *   live-overlay candidates spliced on top of [serverTrails].
 * - [singleTrailSeed] is the canonical list for the single-tracker render path. For
 *   `SINGLE_SERVER` it is the server geometry of the displayed tracker; for `SINGLE_QUEUE`
 *   it is the local queue; for `MULTI_SERVER` it mirrors `serverTrails[activeTrackerId]` so
 *   the single-trail fallback inside multi mode is consistent.
 *
 * The class is a plain data carrier — there is no mutation API, so server geometry and the
 * local queue remain separate lanes through the whole pipeline.
 */
data class TrackerMapTrailLoadResult(
    val serverTrails: Map<String, List<QueuedLocation>>,
    val queueOverlaysByTracker: Map<String, List<QueuedLocation>>,
    val singleTrailSeed: List<QueuedLocation>,
) {
    companion object {
        val EMPTY: TrackerMapTrailLoadResult = TrackerMapTrailLoadResult(
            serverTrails = emptyMap(),
            queueOverlaysByTracker = emptyMap(),
            singleTrailSeed = emptyList(),
        )
    }
}

/**
 * IO seam for [TrackerMapTrailLoader]. Function-typed so the loader can be exercised as a pure
 * unit without a `TrackerMapViewModel` instance, mirroring the style of
 * [TrackerMapTrailDataCoordinator].
 */
class TrackerMapTrailLoaderOps(
    val loadSingleServer: suspend (trackerId: String, existingTrailMinTimeMs: Long?) -> List<QueuedLocation>,
    val loadMultiServer: suspend (trackerIds: Collection<String>, existingMultiMinTimes: Map<String, Long>) -> Map<String, List<QueuedLocation>>,
    val loadQueue: suspend (trackerId: String) -> List<QueuedLocation>,
)

/**
 * Resolves a [TrackerMapTrailReloadPlan] into concrete trail data. Server geometry and the
 * local-queue overlay are returned as separate fields so the caller's merge step can treat
 * the queue as live-overlay candidates without ever clobbering server history.
 */
object TrackerMapTrailLoader {
    suspend fun load(
        plan: TrackerMapTrailReloadPlan,
        existingTrailMinTimeMs: Long?,
        existingMultiMinTimes: Map<String, Long>,
        ops: TrackerMapTrailLoaderOps,
    ): TrackerMapTrailLoadResult {
        return when (plan.source) {
            TrackerMapTrailSource.SINGLE_SERVER -> loadSingleServer(plan, existingTrailMinTimeMs, ops)
            TrackerMapTrailSource.MULTI_SERVER -> loadMultiServer(plan, existingMultiMinTimes, ops)
            TrackerMapTrailSource.SINGLE_QUEUE -> loadSingleQueue(plan, ops)
        }
    }

    private suspend fun loadSingleServer(
        plan: TrackerMapTrailReloadPlan,
        existingTrailMinTimeMs: Long?,
        ops: TrackerMapTrailLoaderOps,
    ): TrackerMapTrailLoadResult {
        val seed = ops.loadSingleServer(plan.singleTrackerId, existingTrailMinTimeMs)
        val queueOverlays = queueOverlayFor(plan.overlayTrackerId, ops)
        return TrackerMapTrailLoadResult(
            serverTrails = emptyMap(),
            queueOverlaysByTracker = queueOverlays,
            singleTrailSeed = seed,
        )
    }

    private suspend fun loadMultiServer(
        plan: TrackerMapTrailReloadPlan,
        existingMultiMinTimes: Map<String, Long>,
        ops: TrackerMapTrailLoaderOps,
    ): TrackerMapTrailLoadResult {
        val serverTrails = ops.loadMultiServer(plan.trackerIds, existingMultiMinTimes)
        val queueOverlays = queueOverlayFor(plan.overlayTrackerId, ops)
        val activeId = plan.activeTrackerId.trim()
        val singleSeed = if (activeId.isNotEmpty()) serverTrails[activeId].orEmpty() else emptyList()
        return TrackerMapTrailLoadResult(
            serverTrails = serverTrails,
            queueOverlaysByTracker = queueOverlays,
            singleTrailSeed = singleSeed,
        )
    }

    private suspend fun loadSingleQueue(
        plan: TrackerMapTrailReloadPlan,
        ops: TrackerMapTrailLoaderOps,
    ): TrackerMapTrailLoadResult {
        return TrackerMapTrailLoadResult(
            serverTrails = emptyMap(),
            queueOverlaysByTracker = emptyMap(),
            singleTrailSeed = ops.loadQueue(plan.activeTrackerId),
        )
    }

    private suspend fun queueOverlayFor(
        overlayTrackerId: String?,
        ops: TrackerMapTrailLoaderOps,
    ): Map<String, List<QueuedLocation>> {
        val normalized = overlayTrackerId?.trim().orEmpty()
        if (normalized.isEmpty()) return emptyMap()
        val rows = ops.loadQueue(normalized)
        if (rows.isEmpty()) return emptyMap()
        return mapOf(normalized to rows)
    }
}
