package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.policy.TrackPointEvent

data class TrackerMapSessionBuildInput(
    val state: TrackerMapUiState,
    val plan: TrackerMapStreamingPlan,
    val localRuntimeOverlayTrails: Map<String, List<QueuedLocation>> = emptyMap(),
    val recentDataWindowByTracker: Map<String, String?> = emptyMap(),
    /**
     * Authoritative current-session start, keyed by tracker id. Populated only for
     * the locally-recorded tracker; foreign trackers are absent (their session start
     * is inferred from points). See [TrackerSessionAttributionPolicy].
     */
    val currentSessionStartByTracker: Map<String, Long> = emptyMap(),
    val nowMs: Long = System.currentTimeMillis(),
)

data class TrackerMapSessionPointInput(
    val snapshot: TrackerMapSessionSnapshot,
    val point: TrackPointEvent,
    val trailPointLimit: Int,
    val recentDataWindowByTracker: Map<String, String?> = emptyMap(),
    /** See [TrackerMapSessionBuildInput.currentSessionStartByTracker]. */
    val currentSessionStartByTracker: Map<String, Long> = emptyMap(),
    val nowMs: Long = System.currentTimeMillis(),
)

data class TrackerMapSessionPointResult(
    val acceptedBySourcePolicy: Boolean,
    val shouldUpdate: Boolean,
    val nextSnapshot: TrackerMapSessionSnapshot,
)

object TrackerMapSessionEngine {
    fun build(input: TrackerMapSessionBuildInput): TrackerMapSessionSnapshot {
        val state = input.state
        val plan = input.plan
        val acceptedRemoteLastPoints = state.remoteLastPoints.filterKeys { it in plan.acceptedRemoteTrackerIds }
        val normalizedTrails = input.localRuntimeOverlayTrails.mapKeys { it.key.trim() }
            .filterKeys { it.isNotEmpty() }
        // Render trails for any tracker we have data for. We intentionally do NOT gate keys on
        // plan.acceptedRemoteTrackerIds: server history loaded for multi-mode (group/all) trackers
        // must remain visible even when the live stream's accepted set transiently flickers (e.g.
        // service start lag, reconciliation). Marker heads from acceptedRemoteLastPoints are still
        // gated by plan.acceptedRemoteTrackerIds via the filter above.
        val tracks = (normalizedTrails.keys + acceptedRemoteLastPoints.keys).associateWith { trackerId ->
            val rawTrail = normalizedTrails[trackerId].orEmpty()
            val filteredTrail = TrackerMapRecentDataWindowFilterPolicy.apply(
                points = rawTrail,
                context = TrackerSessionWindowContext(
                    windowKey = input.recentDataWindowByTracker[trackerId],
                    nowMs = input.nowMs,
                    currentSessionStartMs = input.currentSessionStartByTracker[trackerId],
                ),
            )
            val split = splitTrail(filteredTrail)
            TrackerTrackModel(
                trackerId = trackerId,
                historicalTrail = split.historicalTrail,
                liveTrail = split.liveTrail,
                remoteHead = acceptedRemoteLastPoints[trackerId],
            )
        }
        // Single-trail renders the displayed tracker (mirrors `activeTrackerId =
        // sessionPlan.displayedTrackerId` in the view model). The plan does not carry a separate
        // activeTrackerId field; displayedTrackerId is the canonical anchor when single-mode.
        val displayedKey = plan.displayedTrackerId.trim().takeIf { it.isNotEmpty() }
        val singleTrailWindowKey = displayedKey?.let { input.recentDataWindowByTracker[it] }
        val singleTrailCurrentSessionStartMs = displayedKey?.let { input.currentSessionStartByTracker[it] }
        val filteredSingleTrail = TrackerMapRecentDataWindowFilterPolicy.apply(
            points = state.trail,
            context = TrackerSessionWindowContext(
                windowKey = singleTrailWindowKey,
                nowMs = input.nowMs,
                currentSessionStartMs = singleTrailCurrentSessionStartMs,
            ),
        )
        val singleSplit = splitTrail(filteredSingleTrail)
        return TrackerMapSessionSnapshot(
            uiState = state,
            plan = plan,
            runtime = state.runtime,
            singleTrail = singleSplit.historicalTrail + singleSplit.liveTrail,
            tracks = tracks,
            acceptedRemoteLastPoints = acceptedRemoteLastPoints,
        )
    }

    fun reducePoint(input: TrackerMapSessionPointInput): TrackerMapSessionPointResult {
        val reduction = TrackerMapPointEventReducer.reduce(
            TrackerMapPointReductionInput(
                state = input.snapshot.uiState,
                point = input.point,
                trailPointLimit = input.trailPointLimit,
                sessionPlan = input.snapshot.plan,
            )
        )
        if (!reduction.shouldUpdateUiState) {
            return TrackerMapSessionPointResult(
                acceptedBySourcePolicy = reduction.acceptedBySourcePolicy,
                shouldUpdate = false,
                nextSnapshot = input.snapshot,
            )
        }
        val nextSnapshot = build(
            TrackerMapSessionBuildInput(
                state = reduction.nextState,
                plan = input.snapshot.plan.copy(
                    acceptedRemoteTrackerIds = input.snapshot.plan.acceptedRemoteTrackerIds,
                ),
                localRuntimeOverlayTrails = input.snapshot.renderTrailsByTracker + reduction.nextState.allQueueTrailsByTracker,
                recentDataWindowByTracker = input.recentDataWindowByTracker,
                currentSessionStartByTracker = input.currentSessionStartByTracker,
                nowMs = input.nowMs,
            )
        )
        return TrackerMapSessionPointResult(
            acceptedBySourcePolicy = reduction.acceptedBySourcePolicy,
            shouldUpdate = true,
            nextSnapshot = nextSnapshot,
        )
    }

    private fun splitTrail(trail: List<QueuedLocation>): TrackerTrackModel {
        val live = trail.filter(TrackerMapPointProvenancePolicy::isLiveOverlay)
        val historical = trail.filterNot(TrackerMapPointProvenancePolicy::isLiveOverlay)
        return TrackerTrackModel(
            trackerId = trail.firstOrNull()?.trackerId.orEmpty(),
            historicalTrail = historical,
            liveTrail = live,
        )
    }
}
