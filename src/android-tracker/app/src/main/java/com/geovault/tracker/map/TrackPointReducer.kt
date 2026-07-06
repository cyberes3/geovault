package com.geovault.tracker.map

import com.geovault.common.logging.CaptureLogThrottle
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.presentation.TrackerMapHistoryUiSync
import com.geovault.tracker.presentation.TrackerMapPointRouter
import com.geovault.tracker.presentation.TrackerMapUiState
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
    internal fun reduce(point: TrackPointEvent) {
        // PRE-CHECK, NOT AUTHORITATIVE: a cheap fast-path reject against the current snapshot so
        // the overwhelmingly common "not in scope for this viewport" point (e.g. a streamed
        // tracker nobody is viewing) never has to enter `update {}` at all. This decision is
        // NOT reused for the actual mutation below -- see CONTEXT-SWITCH RE-VALIDATION.
        val precheckPlan = rt.streamingPlanCache.resolve(rt.stateHub.uiStateMutable.value, rt::projectSession)
        if (!TrackerMapPointRouter.route(point, precheckPlan).accepted) {
            logReduceResult(point, accepted = false, updated = false)
            return
        }
        var shouldUpdate = false
        var rejectedByContextSwitch = false
        // CONTEXT-SWITCH RE-VALIDATION: the plan/route are re-resolved against `latest` --
        // the actual snapshot this write applies against -- INSIDE the atomic block, not reused
        // from the pre-check above. A mode/displayed-tracker switch landing in the gap between
        // the pre-check and this block must never let the point be misattributed to the wrong
        // trail (single vs. multi, wrong tracker bucket) using a now-stale plan. If re-resolving
        // flips the decision to rejected, the point is dropped -- correctly, since the context
        // it was accepted against no longer exists -- and [rejectedByContextSwitch] ensures that
        // drop is still diagnosed below rather than silently disappearing.
        rt.stateHub.uiStateMutable.update { latest ->
            shouldUpdate = false
            rejectedByContextSwitch = false
            val plan = rt.streamingPlanCache.resolve(latest, rt::projectSession)
            val route = TrackerMapPointRouter.route(point, plan)
            if (!route.accepted) {
                rejectedByContextSwitch = true
                return@update latest
            }
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

            // SINGLE-EMISSION POINT-APPLY: applying the trail/remoteLastPoint mutation and
            // refreshing the open selection card's position/accuracy for the same tracker used
            // to be two separate `uiStateMutable` writes (this `update {}` followed by a second
            // `rt.stateHub.uiStateMutable.value = stateWithRefreshedSelectionCard(...)`).
            // `MapStreamingSubsystem` wires several `collect` (not `collectLatest`) consumers
            // straight off `uiStateMutable` (the "render-resync" and "reconcile" collectors), so
            // a collector resumed between the two writes could render a frame where the
            // marker's own trail/remoteLastPoint had already jumped to this point but its open
            // selection card was still showing the stale pre-point position/accuracy for that
            // identical tracker. Computing the selection-card refresh from the same
            // post-trail-apply snapshot inside this single `update {}` block closes that window.
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
        if (rejectedByContextSwitch) {
            logReduceResult(point, accepted = false, updated = false, contextSwitch = true)
            return
        }
        if (shouldUpdate) {
            val nextState = rt.stateHub.uiStateMutable.value
            logReduceResult(point, accepted = true, updated = true, nextState = nextState)
            // NO DIRECT RE-FIT HERE: this used to also call `rt.context.requestFitTrail()`
            // directly when live-active-fit was enabled, racing the "render-resync" collector
            // (`MapStreamingSubsystem`) that reacts to this same `uiStateMutable` write via
            // `publishRenderPackage()` -> `resolveFromLockState()`. Two independent paths could
            // each mint a competing directive for the same point (an Animated `ExplicitFit` from
            // here vs. an Instant `LiveActiveFit` from the reactive path), with whichever ran
            // second silently winning depending on coroutine scheduling. Relying solely on the
            // reactive precedence-driven path removes the race entirely.
        }
    }

    private fun logReduceResult(
        point: TrackPointEvent,
        accepted: Boolean,
        updated: Boolean,
        contextSwitch: Boolean = false,
        nextState: TrackerMapUiState? = null,
    ) {
        val throttleKey = if (accepted) "vm_point_reduce_accept" else "vm_point_reduce_reject"
        val signature = "source=${point.source}|track=${point.trackId.trim()}|accepted=$accepted|ctxSwitch=$contextSwitch"
        if (!CaptureLogThrottle.shouldLogOnChange(throttleKey, signature)) return
        val detail = if (nextState != null) {
            " singleAfter=${nextState.trail.trailSummary()} multiAfter=${nextState.allQueueTrailsByTracker.mapSizes()}"
        } else if (contextSwitch) {
            " reason=context_switch_invalidated_plan"
        } else {
            ""
        }
        GeoVaultCaptureLog.d(
            TrackerMapViewModel.TAG,
            "map_update vm_point_reduce_result source=${point.source} track=${point.trackId.trim()} " +
                "accepted=$accepted update=$updated$detail",
        )
    }
}
