package com.geovault.tracker.presentation

import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.Tracker
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.history.TrackerHistoryDiagnostics
import com.geovault.tracker.history.TrackerHistoryTransactionResult
import com.geovault.tracker.history.TrackerHistoryClearBoundary
import com.geovault.tracker.history.TrackerHistoryIntent
import com.geovault.tracker.history.TrackerHistoryIntentDispatcher
import com.geovault.tracker.history.TrackerHistoryKey
import com.geovault.tracker.history.TrackerHistoryRenderMapper
import com.geovault.tracker.history.TrackerHistoryRenderWindowPolicy
import com.geovault.tracker.history.TrackerHistoryRepository
import com.geovault.tracker.history.TrackerHistorySnapshot
import com.geovault.tracker.history.TrackerHistorySourceAdapters
import com.geovault.tracker.history.TrackerHistoryWindow
import com.geovault.tracker.history.TrackerHistoryProvenance
import com.geovault.tracker.history.TrackerHistoryWindowResolver
import com.geovault.tracker.history.toQueuedLocationProvider
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.services.TrackingRuntimeSnapshot

/**
 * Applies [TrackerHistoryRepository] snapshots onto [TrackerMapUiState] trail fields and
 * synchronizes runtime-head overlay batches before render.
 */
object TrackerMapHistoryUiSync {
    data class TrailsFromHistory(
        val trail: List<QueuedLocation>,
        val allQueueTrailsByTracker: Map<String, List<QueuedLocation>>,
    )

    fun historyWindowForTracker(
        trackerId: String,
        trackers: List<Tracker>,
    ): TrackerHistoryWindow {
        val tracker = trackers.firstOrNull { it.id.trim() == trackerId.trim() }
        return TrackerHistoryWindowResolver.fromTracker(tracker)
    }

    /**
     * Tracker ids that should have history materialized for the current map mode.
     * GROUP uses the same hidden-filtered roster as [TrackerMapSessionEngine.build].
     */
    fun historyTrackerIdsForRender(
        state: TrackerMapUiState,
        plan: TrackerMapStreamingPlan,
        visibleTrackerIds: Set<String>?,
    ): Set<String> {
        return when (state.mode) {
            TrackerMapDisplayMode.SINGLE_SESSION -> {
                val id = plan.displayedTrackerId.trim().ifBlank { state.runtime.selectedTrackerId.trim() }
                if (id.isEmpty()) emptySet() else setOf(id)
            }
            TrackerMapDisplayMode.GROUP_PLACEHOLDER -> {
                visibleTrackerIds
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.toSet()
                    ?: plan.groupTrackerIds
            }
            TrackerMapDisplayMode.ALL_QUEUE -> {
                (plan.visibleRosterTrackerIds + plan.localOverlayTrackerIds)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
            }
        }
    }

    /**
     * Whether history compose should skip client-side [TrackerMapRecentDataWindowFilterPolicy]
     * for this tracker. Session windows never skip; complete trunks skip only when server
     * [Tracker.geometry_status] window matches settings.
     */
    fun shouldSkipClientRenderWindowFilter(
        snapshot: TrackerHistorySnapshot?,
        tracker: Tracker?,
    ): Boolean {
        val window = TrackerHistoryWindowResolver.fromTracker(tracker)
        return TrackerHistoryRenderWindowPolicy.shouldSkipRenderWindowFilter(
            snapshot = snapshot,
            tracker = tracker,
            window = window,
        )
    }

    fun hasAuthoritativeServerTrunk(
        snapshots: Map<TrackerHistoryKey, TrackerHistorySnapshot>,
        trackers: List<Tracker>,
        trackerId: String,
    ): Boolean {
        val normalized = trackerId.trim()
        if (normalized.isEmpty()) return false
        val window = historyWindowForTracker(normalized, trackers)
        val snapshot = snapshots[TrackerHistoryKey(normalized, window)] ?: return false
        return snapshot.trunk.isNotEmpty() && !snapshot.degradedLocalOnly
    }

    fun applySnapshotsToState(
        state: TrackerMapUiState,
        plan: TrackerMapStreamingPlan,
        snapshots: Map<TrackerHistoryKey, TrackerHistorySnapshot>,
        trackers: List<Tracker>,
        trailPointLimit: Int,
        visibleTrackerIds: Set<String>? = null,
    ): TrackerMapUiState {
        val trails = trailsFromSnapshots(
            state = state,
            plan = plan,
            snapshots = snapshots,
            trackers = trackers,
            trailPointLimit = trailPointLimit,
            visibleTrackerIds = visibleTrackerIds,
        )
        return state.copy(
            trail = trails.trail,
            allQueueTrailsByTracker = trails.allQueueTrailsByTracker,
        )
    }

    fun trailsFromSnapshots(
        state: TrackerMapUiState,
        plan: TrackerMapStreamingPlan,
        snapshots: Map<TrackerHistoryKey, TrackerHistorySnapshot>,
        trackers: List<Tracker>,
        trailPointLimit: Int,
        visibleTrackerIds: Set<String>? = null,
    ): TrailsFromHistory {
        val incompleteTrunks = mutableSetOf<String>()
        val degradedTrunks = mutableSetOf<String>()
        val trails = when (state.mode) {
            TrackerMapDisplayMode.SINGLE_SESSION -> {
                val trackerId = plan.displayedTrackerId.trim()
                    .ifBlank { state.runtime.selectedTrackerId.trim() }
                val window = historyWindowForTracker(trackerId, trackers)
                val key = TrackerHistoryKey(trackerId, window)
                val snapshot = snapshots[key]
                val tracker = trackers.firstOrNull { it.id.trim() == trackerId.trim() }
                markSnapshotFlags(snapshot, tracker, trackerId, incompleteTrunks, degradedTrunks)
                val trail = TrackerHistoryRenderMapper.toQueuedLocations(snapshot, trailPointLimit)
                TrailsFromHistory(
                    trail = trail,
                    allQueueTrailsByTracker = state.allQueueTrailsByTracker,
                ) to TrackerHistoryDiagnostics.TrailsDrawSummary(
                    singleCount = trail.size,
                    singleTime = TrackerHistoryDiagnostics.queuedTimeRange(trail),
                    multiSizes = TrackerHistoryDiagnostics.mapSizes(state.allQueueTrailsByTracker),
                    incompleteTrackerIds = incompleteTrunks.toSet(),
                    degradedTrackerIds = degradedTrunks.toSet(),
                )
            }
            TrackerMapDisplayMode.ALL_QUEUE,
            TrackerMapDisplayMode.GROUP_PLACEHOLDER -> {
                val trackerIds = historyTrackerIdsForRender(
                    state = state,
                    plan = plan,
                    visibleTrackerIds = visibleTrackerIds,
                )
                val multi = trackerIds
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .associateWith { trackerId ->
                        val window = historyWindowForTracker(trackerId, trackers)
                        val snapshot = snapshots[TrackerHistoryKey(trackerId, window)]
                        val tracker = trackers.firstOrNull { it.id.trim() == trackerId.trim() }
                        markSnapshotFlags(snapshot, tracker, trackerId, incompleteTrunks, degradedTrunks)
                        TrackerHistoryRenderMapper.toQueuedLocations(snapshot, trailPointLimit)
                    }
                val activeId = plan.displayedTrackerId.trim()
                    .ifBlank { state.runtime.selectedTrackerId.trim() }
                val activeTrail = multi[activeId].orEmpty()
                TrailsFromHistory(
                    trail = activeTrail,
                    allQueueTrailsByTracker = multi,
                ) to TrackerHistoryDiagnostics.TrailsDrawSummary(
                    singleCount = activeTrail.size,
                    singleTime = TrackerHistoryDiagnostics.queuedTimeRange(activeTrail),
                    multiSizes = TrackerHistoryDiagnostics.mapSizes(multi),
                    incompleteTrackerIds = incompleteTrunks.toSet(),
                    degradedTrackerIds = degradedTrunks.toSet(),
                )
            }
        }
        val (result, drawSummary) = trails
        TrackerHistoryDiagnostics.logDrawApply(
            mode = state.mode.name,
            displayedTrackerId = plan.displayedTrackerId.ifBlank { state.runtime.selectedTrackerId },
            trails = drawSummary,
            skipClientWindowFilter = emptySet(),
        )
        return result
    }

    fun syncRuntimeHeadOverlay(
        runtime: TrackingRuntimeSnapshot,
        plan: TrackerMapStreamingPlan,
        snapshots: Map<TrackerHistoryKey, TrackerHistorySnapshot>,
        trackers: List<Tracker>,
        dispatcher: TrackerHistoryIntentDispatcher,
        trailPointLimit: Int,
    ) {
        if (!runtime.localRecordingActive) return
        val trackerId = runtime.locallyRecordedTrackerId.trim()
        if (trackerId.isEmpty()) return
        val shouldOverlay = when (plan.mode) {
            TrackerMapDisplayMode.SINGLE_SESSION -> {
                val displayed = plan.displayedTrackerId.trim().ifBlank { runtime.selectedTrackerId.trim() }
                displayed == trackerId
            }
            TrackerMapDisplayMode.ALL_QUEUE,
            TrackerMapDisplayMode.GROUP_PLACEHOLDER -> trackerId in plan.localOverlayTrackerIds
        }
        if (!shouldOverlay) return
        val point = runtimeHeadQueuedLocation(runtime, trackerId) ?: return
        val window = historyWindowForTracker(trackerId, trackers)
        val key = TrackerHistoryKey(trackerId, window)
        val composed = snapshots[key]?.points.orEmpty()
        val last = composed.lastOrNull()
        if (last != null &&
            last.timestampMs >= point.time &&
            last.startTimestampMs == point.startTimestampMs
        ) {
            TrackerHistoryDiagnostics.logRuntimeHead(trackerId, "skip_already_composed", point.time)
            return
        }
        TrackerHistoryDiagnostics.logRuntimeHead(trackerId, "commit", point.time)
        dispatcher.dispatch(
            TrackerHistoryIntent.CommitOverlay(
                batch = TrackerHistorySourceAdapters.runtimeHeadOverlay(point, window),
                activeSessionStartMs = runtime.sessionStartTimeMs.takeIf { it > 0L },
            )
        )
    }

    fun commitQueueOverlays(
        queueOverlaysByTracker: Map<String, List<QueuedLocation>>,
        trackers: List<Tracker>,
        dispatcher: TrackerHistoryIntentDispatcher,
        activeSessionStartMs: Long?,
    ) {
        val committed = mutableListOf<String>()
        queueOverlaysByTracker.forEach { (trackerId, overlay) ->
            if (overlay.isEmpty()) return@forEach
            val window = historyWindowForTracker(trackerId, trackers)
            val result = dispatcher.dispatch(
                TrackerHistoryIntent.CommitOverlay(
                    batch = TrackerHistorySourceAdapters.localQueueOverlay(
                        trackerId = trackerId,
                        window = window,
                        queuedLocations = overlay,
                    ),
                    activeSessionStartMs = activeSessionStartMs,
                ),
            )
            if (result.committed) {
                committed += "$trackerId:${overlay.size}@${window.normalizedKey}"
            }
        }
        if (committed.isNotEmpty()) {
            com.geovault.common.logging.GeoVaultCaptureLog.i(
                "TrackerHistory",
                "map_update history_queue_overlay_commit count=${committed.size} " +
                    "trackers=${committed.joinToString()}",
            )
        }
    }

    fun dispatchHistoryClear(
        trackerId: String,
        trackers: List<Tracker>,
        dispatcher: TrackerHistoryIntentDispatcher,
        activeSessionStartMs: Long?,
        clearedAtMs: Long = System.currentTimeMillis(),
    ) {
        val normalized = trackerId.trim()
        if (normalized.isEmpty()) return
        val window = historyWindowForTracker(normalized, trackers)
        dispatcher.dispatch(
            TrackerHistoryIntent.Clear(
                boundary = TrackerHistoryClearBoundary(
                    trackerId = normalized,
                    clearedAtMs = clearedAtMs,
                    activeSessionStartMs = activeSessionStartMs,
                ),
                window = window,
            )
        )
    }

    fun dispatchLiveOverlay(
        point: TrackPointEvent,
        trackers: List<Tracker>,
        dispatcher: TrackerHistoryIntentDispatcher,
        activeSessionStartMs: Long?,
    ): Boolean {
        val trackerId = point.trackId.trim()
        if (trackerId.isEmpty()) return false
        val window = historyWindowForTracker(trackerId, trackers)
        val batch = TrackerHistorySourceAdapters.liveOverlay(
            event = point,
            window = window,
            activeSessionStartMs = activeSessionStartMs,
        )
        val result = dispatcher.dispatch(
            TrackerHistoryIntent.CommitOverlay(
                batch = batch,
                activeSessionStartMs = activeSessionStartMs,
            ),
        )
        if (result.committed) return true
        if (batch.points.isEmpty()) return false
        // Overlay is in the source store but compose deferred (e.g. empty trunk reload); still refresh UI.
        return result.reason == "empty_snapshot_deferred"
    }

    private fun runtimeHeadQueuedLocation(
        runtime: TrackingRuntimeSnapshot,
        trackerId: String,
    ): QueuedLocation? {
        val lat = runtime.lastTrackedLatitude ?: return null
        val lon = runtime.lastTrackedLongitude ?: return null
        val runtimeTs = runtime.lastTrackedTimestampMs
        if (runtimeTs <= 0L) return null
        return QueuedLocation(
            id = 0L,
            trackerId = trackerId,
            time = runtimeTs,
            latitude = lat,
            longitude = lon,
            altitude = null,
            speed = null,
            bearing = null,
            accuracy = runtime.lastAccuracyMeters,
            sat = null,
            prov = TrackerHistoryProvenance.RUNTIME_HEAD.toQueuedLocationProvider(),
            dist = null,
            startTimestampMs = runtime.sessionStartTimeMs.takeIf { it > 0L },
        )
    }

    private fun markSnapshotFlags(
        snapshot: TrackerHistorySnapshot?,
        tracker: Tracker?,
        trackerId: String,
        incompleteTrunks: MutableSet<String>,
        degradedTrunks: MutableSet<String>,
    ) {
        if (snapshot == null) return
        val id = trackerId.trim()
        if (!snapshot.complete) incompleteTrunks += id
        if (snapshot.degradedLocalOnly) degradedTrunks += id
    }
}
