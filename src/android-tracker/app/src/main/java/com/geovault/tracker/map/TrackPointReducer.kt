package com.geovault.tracker.map

import com.geovault.common.logging.CaptureLogThrottle
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.presentation.TrackerMapHistoryUiSync
import com.geovault.tracker.presentation.TrackerMapPointRouter
import com.geovault.tracker.presentation.TrackerMapViewModel
import kotlinx.coroutines.flow.update

/**
 * Applies a single incoming [TrackPointEvent] -- a local GPS fix or a remote streamed point --
 * to the map's UI state: decides whether it's in-scope for the current mode/roster via
 * [TrackerMapPointRouter], then updates the remote-last-point cache and/or appends it to the
 * live trail overlay.
 *
 * This is the single highest-traffic path in the map (every point, from every streamed
 * tracker, funnels through [reduce]), so it is kept as its own collaborator rather than folded
 * into [MapStreamingSubsystem] alongside the much-lower-frequency roster-resolution and
 * reconcile-loop concerns.
 */
internal class TrackPointReducer(private val rt: TrackerMapRuntime) {
    fun reduce(point: TrackPointEvent) {
        // SINGLE-SNAPSHOT ROUTING: this used to compute the plan/route twice — once here,
        // once again inside the `update {}` block against whatever `latest` state the CAS
        // loop happened to see. A mode change landing between the two computed a *different*
        // route the second time, and the block silently returned `latest` unchanged: the
        // point was already past the "accepted" branch above (so never logged as rejected)
        // yet never applied either — permanently lost with zero diagnostics and no retry. One
        // plan/route snapshot is now computed once and reused for both the accept/reject
        // decision and the mutation itself, so the two can never disagree. Resolved through
        // the cache since this runs once per incoming point across every streamed tracker --
        // see TrackerMapStreamingPlanCache for why a fresh per-point roster scan doesn't scale.
        val plan = rt.streamingPlanCache.resolve(rt.stateHub.uiStateMutable.value, rt::projectSession)
        val route = TrackerMapPointRouter.route(point, plan)
        if (!route.accepted) {
            if (CaptureLogThrottle.shouldLogOnChange(
                    "vm_point_reduce_reject",
                    "source=${point.source}|track=${point.trackId.trim()}|accepted=false",
                )
            ) {
                GeoVaultCaptureLog.d(
                    TrackerMapViewModel.TAG,
                    "map_update vm_point_reduce_result source=${point.source} track=${point.trackId.trim()} " +
                        "accepted=false update=false",
                )
            }
            return
        }
        var shouldUpdate = false
        // SINGLE-EMISSION POINT-APPLY: applying the trail/remoteLastPoint mutation and
        // refreshing the open selection card's position/accuracy for the same tracker used to
        // be two separate `uiStateMutable` writes (this `update {}` followed by a second
        // `rt.stateHub.uiStateMutable.value = stateWithRefreshedSelectionCard(...)`). `MapStreamingSubsystem`
        // wires several `collect` (not `collectLatest`) consumers straight off `uiStateMutable`
        // (the "render-resync" and "reconcile" collectors), so a collector resumed between the
        // two writes could render a frame where the marker's own trail/remoteLastPoint had
        // already jumped to this point but its open selection card was still showing the
        // stale pre-point position/accuracy for that identical tracker. Computing the
        // selection-card refresh from the same post-trail-apply snapshot inside this single
        // `update {}` block closes that window.
        rt.stateHub.uiStateMutable.update { latest ->
            var next = latest

            if (route.updateRemoteLastPoint) {
                val remoteTrackerId = route.normalizedTrackerId
                next = next.copy(
                    remoteLastPoints = next.remoteLastPoints.toMutableMap().apply {
                        this[remoteTrackerId] = point.copy(trackId = remoteTrackerId)
                    },
                )
                shouldUpdate = true
            }

            if (route.appendSingleTrail || route.appendMultiTrail) {
                val overlayCommitted = TrackerMapHistoryUiSync.dispatchLiveOverlay(
                    point = point,
                    trackers = rt.dependencies.trackerManagementStateStore.trackers.value,
                    dispatcher = rt.dependencies.historyIntentDispatcher,
                    activeSessionStartMs = rt.activeSessionStartMsForRuntime(latest.runtime),
                )
                if (overlayCommitted) {
                    shouldUpdate = true
                }
            }

            if (!shouldUpdate) return@update latest
            val withTrails = rt.display.applyHistoryTrailsToState(next, plan)
            rt.context.stateWithRefreshedSelectionCard(withTrails, point.trackId)
        }
        if (shouldUpdate) {
            val nextState = rt.stateHub.uiStateMutable.value
            if (CaptureLogThrottle.shouldLogOnChange(
                    "vm_point_reduce_accept",
                    "source=${point.source}|track=${point.trackId.trim()}",
                )
            ) {
                GeoVaultCaptureLog.d(
                    TrackerMapViewModel.TAG,
                    "map_update vm_point_reduce_result source=${point.source} track=${point.trackId.trim()} " +
                        "accepted=true update=true singleAfter=${nextState.trail.trailSummary()} " +
                        "multiAfter=${nextState.allQueueTrailsByTracker.mapSizes()}",
                )
            }
            if (nextState.liveActiveFitEnabled) {
                rt.context.requestFitTrail()
            }
        }
    }
}
