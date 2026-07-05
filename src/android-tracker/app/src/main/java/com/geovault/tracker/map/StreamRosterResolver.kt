package com.geovault.tracker.map

import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.MapStreamingServiceHelper
import com.geovault.tracker.SelectedTrackerManager
import com.geovault.tracker.SelectedTrackerPrefs
import com.geovault.tracker.presentation.TrackerMapAutoLockPolicy
import com.geovault.tracker.presentation.TrackerMapDisplayMode
import com.geovault.tracker.presentation.TrackerMapFilterChangeReactor
import com.geovault.tracker.presentation.TrackerMapGroupModeSelection
import com.geovault.tracker.presentation.TrackerMapReloadSeedPolicy
import com.geovault.tracker.presentation.TrackerMapRosterRemovalPolicy
import com.geovault.tracker.presentation.TrackerMapStreamSeedInput
import com.geovault.tracker.presentation.TrackerMapStreamingPlan
import com.geovault.tracker.presentation.TrackerMapTrailReloadReason
import com.geovault.tracker.presentation.TrackerMapUiState
import com.geovault.tracker.presentation.TrackerMapViewModel
import com.geovault.tracker.presentation.withAllMapLocksDisabled
import kotlinx.coroutines.flow.update

/**
 * Resolves *what should be streamed/displayed* given the current roster, group membership,
 * visibility settings, and per-tracker filter windows -- and reacts when that resolution needs
 * to change: a tracker's filter window changed, a tracker fell out of the roster entirely
 * (deleted or unshared), or a persisted id from a previous process needs validating against the
 * roster this process actually sees on cold start.
 *
 * Deliberately separate from [StreamTargetReconciler]: this class decides *which trackers*
 * belong on screen/streamed; the reconciler takes that decision (via `streamTargetIds` on
 * [com.geovault.tracker.presentation.TrackerMapUiState]) and turns it into an actual WebSocket
 * lease. [refreshStreamTargets] is the bridge between the two -- it writes the resolved
 * `streamTargetIds` that the reconciler's combined flow subsequently reacts to.
 */
internal class StreamRosterResolver(private val rt: TrackerMapRuntime) {
    fun refreshStreamTargets() {
        val state = rt.uiStateMutable.value
        val groupSelection = rt.resolveGroupModeSelection(state)
        val visibleRosterTrackerIds = rt.visibleMapRosterTrackerIds()
        val plan = rt.projectSession(
            state = state,
            groupSelection = groupSelection,
            visibleRosterTrackerIds = visibleRosterTrackerIds,
        )
        val previousStreamTargetIds = state.streamTargetIds
        applyStreamTargetPlan(state, plan, previousStreamTargetIds)
        requestTrailReloadForStreamingScopeChange(state, plan, groupSelection, previousStreamTargetIds)
    }

    /**
     * STREAMING-TARGET PLANNING: writes the resolved plan's targeting decision onto ui state --
     * which trackers are subscribed, the accepted-remote-id filter applied to cached
     * last-points, the resolved group id/options (GROUP mode), and the auto-selection-lock
     * transition that piggybacks on a single-stream target change. Deliberately does not decide
     * whether any of this warrants a server trail reload; see
     * [requestTrailReloadForStreamingScopeChange] for that half.
     */
    private fun applyStreamTargetPlan(
        state: TrackerMapUiState,
        plan: TrackerMapStreamingPlan,
        previousStreamTargetIds: Set<String>,
    ) {
        val nextStreamTargetIds = plan.remoteSubscriptionIds
        val autoSelectionLockId = TrackerMapAutoLockPolicy.resolveAutoSelectionLockForSingleStream(
            mode = state.mode,
            previousTargets = previousStreamTargetIds,
            nextTargets = nextStreamTargetIds,
            displayedTrackerId = plan.displayedTrackerId,
        )
        rt.uiStateMutable.update { cur ->
            val baseNext = cur.copy(
                streamTargetIds = nextStreamTargetIds,
                remoteLastPoints = TrackerMapViewModel.filterRemoteLastPointsForAcceptedIds(
                    remoteLastPoints = cur.remoteLastPoints,
                    acceptedRemoteTrackerIds = plan.acceptedRemoteTrackerIds,
                ),
                currentGroupId = if (state.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
                    plan.resolvedGroupId
                } else {
                    cur.currentGroupId
                },
                groupModeOptions = if (state.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
                    rt.resolveGroupModeOptions()
                } else {
                    emptyList()
                },
            )
            val nextState = if (autoSelectionLockId != null) {
                baseNext.withAllMapLocksDisabled().copy(selectionLockTrackerId = autoSelectionLockId)
            } else {
                baseNext
            }
            if (nextState == cur) cur else nextState
        }
        // Warm the cache with the plan just computed above (from fresh, explicitly-resolved
        // group/roster inputs) keyed against the *post-update* state, since currentGroupId may
        // have just changed to plan.resolvedGroupId -- that's the state the next
        // TrackPointReducer/StreamTargetReconciler call will actually check the signature against.
        rt.streamingPlanCache.warm(rt.uiStateMutable.value, plan)
    }

    /**
     * TRAIL-RELOAD-SEED PLANNING: independent of the ui-state write above, decides whether the
     * *scope* of what's being streamed changed enough to warrant an actual StreamingStart server
     * reload (new geometry to fetch for a newly-added target). Uses its own seed/dedupe
     * signature (`lastStreamTargetsSeed`) -- deliberately distinct from
     * [com.geovault.tracker.map.MapTrailReloadSubsystem]'s own `trailSeed`/`lastTrailLoadSeed` --
     * so a no-op `refreshStreamTargets` call (nothing about targeting changed) never re-fires a
     * reload, without that gate having any influence on the targeting decision itself.
     */
    private fun requestTrailReloadForStreamingScopeChange(
        state: TrackerMapUiState,
        plan: TrackerMapStreamingPlan,
        groupSelection: TrackerMapGroupModeSelection,
        previousStreamTargetIds: Set<String>,
    ) {
        val seed = TrackerMapReloadSeedPolicy.streamSeed(
            TrackerMapStreamSeedInput(
                mode = plan.mode,
                runtimeRunning = state.runtime.localRecordingActive,
                selectedTrackerId = plan.selectedTrackerId,
                displayedTrackerId = plan.displayedTrackerId,
                rosterTrackerIds = plan.visibleRosterTrackerIds,
                groupSelection = groupSelection
            )
        )
        val seedChanged = seed != rt.lastStreamTargetsSeed
        rt.lastStreamTargetsSeed = seed
        val nextStreamTargetIds = plan.remoteSubscriptionIds
        val shouldLoadHistoryForStreamingStart = seedChanged &&
            nextStreamTargetIds.isNotEmpty() &&
            nextStreamTargetIds != previousStreamTargetIds
        if (shouldLoadHistoryForStreamingStart) {
            rt.reload.requestRuntimeTrailReload(TrackerMapTrailReloadReason.StreamingStart)
        }
    }

    suspend fun handleFilterChange(change: TrackerMapFilterChangeReactor.FilterChange) {
        when (change) {
            is TrackerMapFilterChangeReactor.FilterChange.None -> Unit
            is TrackerMapFilterChangeReactor.FilterChange.Refresh -> {
                // Filter changed for a tracker we know. Invalidate the geometry dedupe entry
                // first so the imminent reload reaches the server with the new window; then
                // republish the render package for instant client-side re-filter on points we
                // already hold; then request the forced reload that will overwrite those with the
                // server's window-bounded response.
                rt.sessionRequestDeduper.invalidate(change.trackerId)
                rt.recomposeHistoryForTracker(change.trackerId)
                rt.display.publishRenderPackage()
                rt.reload.requestRuntimeTrailReload(TrackerMapTrailReloadReason.RecentDataWindowChanged)
            }
        }
    }

    /**
     * A tracker id dropped out of the roster entirely — deleted (own tracker), an accepted
     * share revoked server-side, or the owner stopped sharing. Unlike a normal
     * `TrackersRefreshed`/filter-change reaction, there is no future point event or geometry
     * response ever coming for this id again, so any displayed/streamed/cached state for it
     * must be torn down explicitly here rather than left to age out on its own.
     */
    suspend fun handleTrackerRemovedFromRoster(trackerId: String) {
        val outcome = TrackerMapRosterRemovalPolicy.applyRemoval(rt.uiStateMutable.value, trackerId)
        if (!outcome.changed) return
        rt.uiStateMutable.value = outcome.nextState
        // STALE-PLAN-CACHE GUARD: `TrackerMapStreamingPlanCache` is keyed in part on
        // `state.renderMetadataSignature`, which is only refreshed by the independent
        // "roster-fingerprint" collector reacting to the tracker/group/visibility store flows --
        // a *different* collector than the one that delivered the `TrackerDeleted`/
        // `TrackersRefreshed` event that got us here, with no ordering guarantee between them.
        // If a point for `trackerId` (or a reconcile tick) hits `streamingPlanCache.resolve`
        // before that other collector catches up, the signature would still match the
        // pre-removal plan and the cache would serve it back verbatim, resurrecting the tracker
        // we just tore down. Warming the cache here -- synchronously, against the state we just
        // wrote -- closes that gap regardless of the other collector's timing, and covers every
        // caller (including `validateColdStartAgainstRoster`'s cold-start loop, which never calls
        // `refreshStreamTargets()` itself).
        rt.streamingPlanCache.warm(rt.uiStateMutable.value, rt.projectSession(rt.uiStateMutable.value))
        GeoVaultCaptureLog.i(
            TrackerMapViewModel.TAG,
            "roster_removal trackerId=$trackerId shouldRefreshStreamTargets=${outcome.shouldRefreshStreamTargets}"
        )
        rt.display.publishRenderPackage()
    }

    /**
     * COLD-START ROSTER VALIDATION: a persisted displayed/selected/streamed tracker id can
     * reference a tracker deleted or unshared while this process was dead. Left unvalidated,
     * every one of those ids drives a doomed fetch (geometry load, stream subscribe) that can
     * never succeed — clear them proactively instead. Deliberately conservative about
     * `SelectedTrackerPrefs`' `selectedTrackerId`: unlike the displayed/streamed ids (handled
     * via the same side-effect-free [handleTrackerRemovedFromRoster] the live removal path
     * uses), touching the user's own recording-target selection while local recording is
     * active could interrupt an in-progress session as an unintended side effect of a
     * startup validation pass, so that case is left alone entirely — the same is true of any
     * live roster removal, per [TrackerMapRosterRemovalPolicy]'s scope note.
     */
    suspend fun validateColdStartAgainstRoster(rosterIds: Set<String>) {
        val state = rt.uiStateMutable.value
        val displayedId = state.displayedTrackerId.trim()
        if (displayedId.isNotEmpty() && displayedId !in rosterIds) {
            handleTrackerRemovedFromRoster(displayedId)
        }
        val cardTrackerId = rt.uiStateMutable.value.selectedMapTracker?.trackerId?.trim().orEmpty()
        if (cardTrackerId.isNotEmpty() && cardTrackerId !in rosterIds) {
            handleTrackerRemovedFromRoster(cardTrackerId)
        }

        val (persistedStreamTargetIds, _) = MapStreamingServiceHelper.persistedTargets(rt.ports.application)
        val invalidStreamTargetIds = persistedStreamTargetIds - rosterIds
        if (invalidStreamTargetIds.isNotEmpty()) {
            MapStreamingServiceHelper.pruneInvalidPersistedTargets(rt.ports.application, rosterIds)
            for (invalidId in invalidStreamTargetIds) {
                handleTrackerRemovedFromRoster(invalidId)
            }
        }

        val runtime = rt.uiStateMutable.value.runtime
        if (!runtime.localRecordingActive) {
            val persistedSelectedId = SelectedTrackerPrefs.selectedTrackerId(rt.ports.application).trim()
            if (persistedSelectedId.isNotEmpty() && persistedSelectedId !in rosterIds) {
                GeoVaultCaptureLog.w(
                    TrackerMapViewModel.TAG,
                    "map_update cold_start_prune_selected_tracker id=$persistedSelectedId",
                )
                SelectedTrackerManager.clearSelectedTracker(rt.ports.application)
            }
        }
    }
}
