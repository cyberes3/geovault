package com.geovault.tracker.map

import android.app.Application
import android.os.SystemClock
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.MapStreamingServiceHelper
import com.geovault.tracker.Tracker
import com.geovault.tracker.policy.StreamingTargetPolicy
import com.geovault.tracker.policy.StreamingTargetPolicyInput
import com.geovault.tracker.presentation.*
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import com.geovault.tracker.streaming.LiveStreamSubscriptionState
import kotlinx.coroutines.flow.update
import com.geovault.tracker.ui.TrackerPointTimestamps
import kotlinx.coroutines.launch

/**
 * Owns the map's "what is the user looking at, and how did they get there" concerns: display
 * mode transitions, map surface/lifecycle bookkeeping (ready/visible/pending-initial-tracker),
 * marker-tap selection and the selection card, lock toggles, and resume/reopen orchestration.
 * This is the subsystem other code calls into when the user (or the OS) changes what the map
 * should be showing; it does not itself own streaming, trail data, or render-package derivation
 * -- those are [MapStreamingSubsystem], [MapTrailReloadSubsystem], and [MapTrailDisplaySubsystem]
 * respectively.
 */
internal class MapContextSubsystem(private val rt: TrackerMapRuntime) {
    // These fields exist only to drive this subsystem's own resume/lifecycle
    // decisions. `mapReady`, `mapSurfaceVisible`, and `pendingInitialTrackerForMap` are read
    // (never written) from a couple of other subsystems -- see the [isMapReady]/
    // [isMapSurfaceVisible]/[hasPendingInitialTrackerForMap] read-only accessors below -- so they
    // stay `private var` here rather than living as loose mutable fields on `TrackerMapRuntime`.
    private var lastBackgroundAtElapsedMs: Long = 0L
    private var mapReady: Boolean = false
    private var pendingResumeEvaluation: Boolean = false
    private var mapSurfaceVisible: Boolean = false
    private var pendingInitialTrackerForMap: Boolean = true
    private var pendingReopenSingleTrackerLoadId: String? = null
    private val reopenOrchestrator = TrackerMapReopenOrchestrator()

    internal val isMapReady: Boolean get() = mapReady
    internal val isMapSurfaceVisible: Boolean get() = mapSurfaceVisible
    internal val hasPendingInitialTrackerForMap: Boolean get() = pendingInitialTrackerForMap

    internal fun setMode(mode: TrackerMapDisplayMode) {
        val groupOptions = if (mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
            rt.resolveGroupModeOptions()
        } else {
            emptyList()
        }
        val preferredGroupId = if (mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
            val currentGroup = rt.stateHub.uiStateMutable.value.currentGroupId.trim()
            currentGroup.takeIf { candidate -> groupOptions.any { it.groupId == candidate } }
                ?: groupOptions.firstOrNull()?.groupId.orEmpty()
        } else {
            ""
        }
        val pendingReopenTrackerId = if (mode == TrackerMapDisplayMode.SINGLE_SESSION) {
            pendingReopenSingleTrackerLoadId
        } else {
            null
        }
        applyMapContextTransition(
            contextOverrides = { latest ->
                latest.copy(mode = mode, currentGroupId = preferredGroupId, groupModeOptions = groupOptions)
            },
            pendingReopenTrackerId = pendingReopenTrackerId,
            reloadReason = TrackerMapTrailReloadReason.MapContextChange,
        )
    }

    internal fun setGroupModeGroup(groupId: String) {
        val normalized = groupId.trim()
        if (normalized.isEmpty()) return
        val state = rt.stateHub.uiStateMutable.value
        if (state.currentGroupId == normalized && state.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
            return
        }
        applyMapContextTransition(
            contextOverrides = { latest ->
                latest.copy(currentGroupId = normalized, mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER)
            },
            pendingReopenTrackerId = null,
            reloadReason = TrackerMapTrailReloadReason.MapContextChange,
        )
    }

    internal fun openTrackerOnMap(trackerId: String, trackerName: String?) {
        val normalizedId = trackerId.trim()
        if (normalizedId.isEmpty()) return
        val state = rt.stateHub.uiStateMutable.value
        val resolvedName = trackerName?.trim().orEmpty().ifBlank {
            if (normalizedId == state.runtime.selectedTrackerId) {
                state.runtime.selectedTrackerName
            } else {
                rt.dependencies.trackerManagementStateStore.trackers.value
                    .firstOrNull { it.id == normalizedId }
                    ?.name
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: state.displayedTrackerName.takeIf { state.displayedTrackerId == normalizedId }
                    ?: normalizedId
            }
        }
        // ALIVE-LOCKS-ON-OPEN: a tracker with recent activity (same "active" window the group
        // bounds resolver uses) is one whose position the user almost certainly wants to keep
        // watching, not a historical track they want framed end-to-end. Engaging the selection
        // lock here -- rather than the default full-extent fit -- keeps the camera centered on
        // its live position at a sane zoom instead of yanking out to the whole trail on open. A
        // dead tracker has no "current position" worth tracking, so it keeps the fit-to-extent
        // behavior.
        val isAlive = TrackerMapGroupBoundsResolver.isTrackerActive(
            trackerId = normalizedId,
            trailsByTracker = state.allQueueTrailsByTracker,
            remoteLastPoints = state.remoteLastPoints,
            trackers = rt.dependencies.trackerManagementStateStore.trackers.value,
            nowMs = System.currentTimeMillis(),
        )
        applyMapContextTransition(
            contextOverrides = { latest ->
                latest.copy(
                    mode = TrackerMapDisplayMode.SINGLE_SESSION,
                    displayedTrackerId = normalizedId,
                    displayedTrackerName = resolvedName,
                    currentGroupId = "",
                    groupModeOptions = emptyList(),
                )
            },
            pendingReopenTrackerId = normalizedId,
            reloadReason = TrackerMapTrailReloadReason.ExplicitTrackerLoad,
            desiredSelectionLockTrackerId = normalizedId.takeIf { isAlive },
        )
    }

    internal fun openGroupOnMap(groupId: String) {
        val normalizedId = groupId.trim()
        if (normalizedId.isEmpty()) return
        val groupOptions = rt.resolveGroupModeOptions()
        val resolvedGroupId = normalizedId.takeIf { candidate ->
            groupOptions.any { it.groupId == candidate }
        } ?: groupOptions.firstOrNull()?.groupId.orEmpty()
        applyMapContextTransition(
            contextOverrides = { latest ->
                latest.copy(
                    mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
                    currentGroupId = resolvedGroupId,
                    groupModeOptions = groupOptions,
                )
            },
            pendingReopenTrackerId = null,
            reloadReason = TrackerMapTrailReloadReason.MapContextChange,
        )
    }

    internal fun restoreSelectedTrackerAfterStreamingStop() {
        restoreSelectedTrackerMapContext()
    }

    internal fun restoreSelectedTrackerMapContext() {
        val state = rt.stateHub.uiStateMutable.value
        val selectedId = state.runtime.selectedTrackerId.trim()
        rt.dependencies.streamingReconciler.stopForegroundStreaming()
        // CHIP-X / POST-STREAM RESTORE: always collapse back to SINGLE_SESSION on the selected
        // tracker. Both entry points (the X on the top-left chip and the auto-restore that fires
        // when streaming ends) want a deterministic return to the user's selected tracker view.
        // Leaving the mode unchanged would only stop the foreground service and then immediately
        // reissue Start via the combined reconcile flow (since GROUP_PLACEHOLDER's
        // streamTargetIds are unchanged), producing a visible "reconnecting" flicker without
        // any actual exit from the group.
        if (selectedId.isBlank()) {
            pendingInitialTrackerForMap = true
            pendingResumeEvaluation = true
            return
        }
        val selectedName = state.runtime.selectedTrackerName
        applyMapContextTransition(
            contextOverrides = { latest ->
                latest.copy(
                    mode = TrackerMapDisplayMode.SINGLE_SESSION,
                    displayedTrackerId = selectedId,
                    displayedTrackerName = selectedName,
                    currentGroupId = "",
                    groupModeOptions = emptyList(),
                )
            },
            pendingReopenTrackerId = selectedId,
            reloadReason = TrackerMapTrailReloadReason.RestoreSelectedAfterStreaming,
        )
    }

    internal fun resolveListNavigationTarget(preferredTrackerIdOverride: String? = null): MapListNavigationTarget {
        val state = rt.stateHub.uiStateMutable.value
        val preferredTrackerId = preferredTrackerIdOverride?.trim().orEmpty().ifBlank {
            TrackerMapDisplayIds.effectiveDisplayedTrackerId(state)
        }.ifBlank {
            state.runtime.selectedTrackerId.trim()
        }.ifBlank { "" }
        val preferredTrackerOwned = rt.dependencies.trackerManagementStateStore.trackers.value
            .firstOrNull { it.id == preferredTrackerId }
            ?.isOwner()
        val currentGroupOwned = rt.dependencies.trackerManagementStateStore.groups.value
            .firstOrNull { it.id == state.currentGroupId.trim() }
            ?.isOwner()
        return MapListNavigationPolicy.resolve(
            mode = state.mode,
            currentGroupId = state.currentGroupId,
            preferredTrackerId = preferredTrackerId,
            isCurrentGroupOwned = currentGroupOwned,
            isPreferredTrackerOwned = preferredTrackerOwned,
        )
    }

    internal fun onTrackerMarkerTapped(trackerId: String) {
        val normalizedTrackerId = trackerId.trim()
        if (normalizedTrackerId.isEmpty()) return
        val snapshot = rt.display.buildCurrentSessionSnapshot()
        val state = snapshot.uiState
        val selection = buildSelectionCard(snapshot, normalizedTrackerId)
        if (selection == null) {
            rt.stateHub.uiStateMutable.value = state.withClearedMapSelectionCard()
            return
        }
        rt.stateHub.uiStateMutable.value = TrackerMapSelectionCardPolicy.applySelectionCard(state, selection)
    }

    internal fun onMapBackgroundTapped(): Boolean {
        val state = rt.stateHub.uiStateMutable.value
        if (!TrackerMapViewModel.resolveBackgroundTapShouldCloseBottomCard(
                isBottomCardVisible = state.isBottomCardVisible,
                hasSelectionCard = state.selectedMapTracker != null
            )
        ) {
            return false
        }
        rt.stateHub.uiStateMutable.value = state.withClearedMapSelectionCard()
        return true
    }

    internal fun clearMapTrackerSelection() {
        onMapBackgroundTapped()
    }

    internal fun focusSelectedTrackerOnMap() {
        val state = rt.stateHub.uiStateMutable.value
        val selection = state.selectedMapTracker ?: return
        openTrackerOnMap(selection.trackerId, selection.trackerName)
    }

    /** Selection-card lock icon: toggles the lock on whichever tracker the card is showing. */
    internal fun toggleSelectedTrackerLock() {
        val state = rt.stateHub.uiStateMutable.value
        val selection = state.selectedMapTracker ?: return
        toggleTrackerLock(selection.trackerId)
    }

    /** Primary map lock FAB in SINGLE_SESSION: toggles the lock on the currently displayed tracker. */
    internal fun toggleDisplayedTrackerLock() {
        val state = rt.stateHub.uiStateMutable.value
        val displayedId = TrackerMapDisplayIds.effectiveDisplayedTrackerId(state)
        if (displayedId.isEmpty()) return
        toggleTrackerLock(displayedId)
    }

    /**
     * Always establishes a fresh lock context via [withAllMapLocksDisabled] rather than
     * composing with whatever was active before -- unlike [setLiveActiveFit], a manual (re-)tap
     * of the lock FAB/icon is the user explicitly picking a new base target, not modifying the
     * current one.
     */
    private fun toggleTrackerLock(trackerId: String) {
        val selectedId = trackerId.trim()
        if (selectedId.isEmpty()) return
        val state = rt.stateHub.uiStateMutable.value
        val nextSelectionLock = if (state.selectionLockTrackerId == selectedId) "" else selectedId
        rt.stateHub.uiStateMutable.value = state.withAllMapLocksDisabled().copy(selectionLockTrackerId = nextSelectionLock)
    }

    internal fun selectionLockPointOrNull(): Pair<Double, Double>? {
        return selectionLockPointOrNull(rt.display.buildCurrentSessionSnapshot())
    }

    internal fun selectionLockPointOrNull(
        snapshot: TrackerMapSessionSnapshot
    ): Pair<Double, Double>? {
        val state = snapshot.uiState
        val trackerId = state.selectionLockTrackerId.trim()
        if (trackerId.isEmpty()) return null
        val point = resolveTrackerPointData(snapshot, trackerId) ?: return null
        return point.latitude to point.longitude
    }

    private fun buildSelectionCard(
        snapshot: TrackerMapSessionSnapshot,
        trackerId: String
    ): TrackerMapSelectionCard? {
        val state = snapshot.uiState
        val tracker = rt.dependencies.trackerManagementStateStore.trackers.value.firstOrNull { it.id == trackerId }
        val point = resolveTrackerPointData(snapshot, trackerId) ?: return null
        val trackerName = tracker?.name
            ?.takeIf { it.isNotBlank() }
            ?: state.displayedTrackerName.takeIf { trackerId == state.displayedTrackerId && it.isNotBlank() }
            ?: state.runtime.selectedTrackerName.takeIf { trackerId == state.runtime.selectedTrackerId && it.isNotBlank() }
            ?: trackerId
        return TrackerMapSelectionCard(
            trackerId = trackerId,
            trackerName = trackerName,
            latitude = point.latitude,
            longitude = point.longitude,
            lastUpdatedMs = TrackerLastReportedAtPolicy.resolve(
                trackerId = trackerId,
                runtime = state.runtime,
                resolverLastUpdatedMs = point.lastUpdatedMs,
            ),
            accuracyMeters = point.accuracyMeters,
            isOwned = tracker?.isOwner() == true,
            serverMetadataUpdatedAtMs = tracker?.let(TrackerPointTimestamps::serverMetadataUpdatedAtMs),
            lastPointParamsMs = tracker?.let(TrackerPointTimestamps::lastPointParamsMs),
        )
    }

    private fun resolveTrackerPointData(
        snapshot: TrackerMapSessionSnapshot,
        trackerId: String
    ): TrackerMapResolvedPoint? {
        val normalizedId = trackerId.trim()
        if (normalizedId.isEmpty()) return null
        val tracker = rt.dependencies.trackerManagementStateStore.trackers.value.firstOrNull { it.id == normalizedId }
        return TrackerMapLastPointResolver.resolve(
            snapshot = snapshot,
            trackerId = trackerId,
            tracker = tracker,
        )
    }

    internal fun stateWithClearedRenderedTrails(
        state: TrackerMapUiState,
        trackerId: String,
    ): TrackerMapUiState {
        val normalizedId = trackerId.trim()
        if (normalizedId.isEmpty()) return state
        val effectiveDisplayedId = TrackerMapDisplayIds.effectiveDisplayedTrackerId(state)
        val clearSingleTrail = state.mode == TrackerMapDisplayMode.SINGLE_SESSION &&
            (effectiveDisplayedId == normalizedId || state.runtime.selectedTrackerId.trim() == normalizedId)
        return state.copy(
            trail = if (clearSingleTrail) emptyList() else state.trail,
            allQueueTrailsByTracker = state.allQueueTrailsByTracker - normalizedId,
            remoteLastPoints = state.remoteLastPoints - normalizedId,
        )
    }

    private fun stateWithResetMapContext(
        state: TrackerMapUiState,
        preservedSingleTrackerId: String? = null,
        desiredSelectionLockTrackerId: String? = null,
    ): TrackerMapUiState {
        val reset = TrackerMapContextResetPolicy.reset(
            TrackerMapContextResetInput(
                state = state,
                preservedSingleTrackerId = preservedSingleTrackerId,
            )
        )
            .withAllMapLocksDisabled()
            .withClearedMapSelectionCard()
        return if (desiredSelectionLockTrackerId != null) {
            reset.copy(selectionLockTrackerId = desiredSelectionLockTrackerId)
        } else {
            reset
        }
    }

    private fun applyMapContextTransition(
        contextOverrides: (TrackerMapUiState) -> TrackerMapUiState,
        pendingReopenTrackerId: String?,
        reloadReason: TrackerMapTrailReloadReason = TrackerMapTrailReloadReason.GenericMapRefresh,
        desiredSelectionLockTrackerId: String? = null,
    ) {
        val preservedSingleTrackerId = if (reloadReason == TrackerMapTrailReloadReason.RestoreSelectedAfterStreaming) {
            pendingReopenTrackerId
        } else {
            null
        }
        // ATOMIC RESET: `contextOverrides` is applied to `latest` -- the freshest snapshot at
        // commit time -- rather than to a snapshot captured back at the top of the calling
        // method. `stateWithResetMapContext`'s trail-preservation logic
        // (`TrackerMapContextResetPolicy.preservedSingleTrackerTrail`) reads the passed-in
        // state's `trail`/`allQueueTrailsByTracker` to decide what survives the reset; using a
        // stale snapshot there could silently drop a live point that arrived in the gap between
        // that earlier read and this write. `update {}` closes that window without needing a
        // suspend/lock: `contextOverrides` itself only sets fixed, non-trail context fields
        // (mode/displayed-tracker/group), so recomputing it per CAS attempt is safe and cheap.
        rt.stateHub.uiStateMutable.update { latest ->
            stateWithResetMapContext(
                state = contextOverrides(latest),
                preservedSingleTrackerId = preservedSingleTrackerId,
                desiredSelectionLockTrackerId = desiredSelectionLockTrackerId,
            )
        }
        // A new viewport must always get its own fresh camera directive to key the consumer's
        // effect off of, even if the precedence engine happens to resolve to the exact same
        // Resolution the previous viewport last emitted (e.g. both currently None while bounds
        // load) -- otherwise the directive id never changes and the new viewport never gets an
        // initial fit.
        rt.cameraCoordinator.resetLastResolution()
        rt.display.reprojectTrailsFromRepository("map_context_transition")
        pendingReopenSingleTrackerLoadId = pendingReopenTrackerId
        // Do NOT arm `pendingReloadCameraFit` here: it would happen well before the queued
        // reload's own guard/refresh/source-skip checks run (the reload is dispatched
        // asynchronously below), so a skip on any of those paths would leave the flag stale for a
        // later, unrelated reload to consume. `MapTrailReloadSubsystem.reloadTrailFromDatabase`
        // arms it itself, immediately before it actually fetches, for exactly this reason.
        rt.reload.invalidateLoadedSeed()
        rt.reload.requestRuntimeTrailReload(reloadReason)
        rt.streamRosterResolver.refreshStreamTargets()
    }

    internal fun onHostPaused() {
        lastBackgroundAtElapsedMs = SystemClock.elapsedRealtime()
    }

    internal fun onHostResumed() {
        // IDLE-ROLLING-WINDOW: re-filter every rolling-window history key against the current
        // clock unconditionally on resume — a background period is exactly when "last N hours"
        // staleness accumulates undetected (see `TrackerMapRuntime.recomputeStaleRollingWindows`).
        // Independent of the surface-visibility guards below, which only gate the *reload*
        // decision for other reasons.
        rt.ports.viewModelScope.launch {
            if (rt.recomputeStaleRollingWindows()) {
                rt.display.publishRenderPackage()
            }
        }
        if (lastBackgroundAtElapsedMs <= 0L || !mapSurfaceVisible) return
        if (!mapReady) {
            pendingResumeEvaluation = true
            return
        }
        evaluateResumeAfterBackground(allowZeroGap = false)
    }

    internal fun onMapSurfaceVisible() {
        mapSurfaceVisible = true
        if (!mapReady) {
            pendingResumeEvaluation = pendingResumeEvaluation ||
                pendingInitialTrackerForMap ||
                lastBackgroundAtElapsedMs > 0L
            return
        }
        evaluateResumeAfterBackground(allowZeroGap = pendingInitialTrackerForMap || pendingResumeEvaluation)
        rt.display.reprojectTrailsFromRepository("map_surface_visible")
        rt.streamTargetReconciler.bumpReconcileToken()
    }

    internal fun onMapSurfaceHidden(markBackground: Boolean = false) {
        mapSurfaceVisible = false
        mapReady = false
        if (markBackground) {
            lastBackgroundAtElapsedMs = SystemClock.elapsedRealtime()
            pendingResumeEvaluation = true
        }
        rt.streamTargetReconciler.bumpReconcileToken()
        rt.ports.viewModelScope.launch {
            rt.sessionRequestDeduper.clear()
        }
    }

    internal fun setMapReady(isReady: Boolean) {
        mapReady = isReady
        if (!mapReady || !pendingResumeEvaluation) return
        pendingResumeEvaluation = false
        evaluateResumeAfterBackground(allowZeroGap = pendingInitialTrackerForMap)
    }

    internal fun evaluateResumeAfterBackground(allowZeroGap: Boolean) {
        val backgroundDurationMs = if (lastBackgroundAtElapsedMs > 0L) {
            SystemClock.elapsedRealtime() - lastBackgroundAtElapsedMs
        } else {
            0L
        }
        if (backgroundDurationMs <= 0L && !allowZeroGap) return
        val state = rt.stateHub.uiStateMutable.value
        val groupSelection = rt.resolveGroupModeSelection(state)
        val hasPendingInitialTracker = pendingInitialTrackerForMap
        val selectedTrackerId = state.runtime.selectedTrackerId.trim()
        if (hasPendingInitialTracker &&
            state.mode == TrackerMapDisplayMode.SINGLE_SESSION &&
            selectedTrackerId.isBlank() &&
            TrackerMapDisplayIds.effectiveDisplayedTrackerId(state).isBlank()
        ) {
            pendingResumeEvaluation = true
            return
        }
        pendingInitialTrackerForMap = false
        val streamRuntime = rt.dependencies.liveStreamSubscriptionRepository.state.value
        // STREAMING-RESUME NO-OP: when a group / all-queue stream is already running with the
        // right targets and we have populated trails for the displayed roster, the WS is the
        // authoritative source and the orchestrator's reload+reconcile pass would only cause
        // a visible "Reconnecting" flicker on resume. Trust the existing wiring; the combined
        // reconcile collector still validates the lease on the next state tick.
        if (
            streamRuntime.wantsSubscription &&
            streamRuntime.subscriptionHealthy &&
            (state.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER ||
                state.mode == TrackerMapDisplayMode.ALL_QUEUE) &&
            streamingActiveTargetsMatchDisplayed(state, streamRuntime, groupSelection) &&
            displayedRosterHasServerHistory(state, groupSelection)
        ) {
            lastBackgroundAtElapsedMs = 0L
            pendingResumeEvaluation = false
            return
        }
        // STREAMING EXCLUSION (resume): the persisted ids are taken at face value. The projector
        // re-applies the only meaningful exclusion (locally-recorded) on the next reconcile pass;
        // pre-filtering selected here previously produced churn whenever resume and the projector
        // disagreed about whether selected belonged in the stream.
        val persistedStreamTargetIds = MapStreamingServiceHelper.persistedTargets(
            context = rt.ports.application,
        ).first
        val unsanitizedResumeStreamTrackerIds = if (streamRuntime.activeTargets.isNotEmpty()) {
            streamRuntime.activeTargets
        } else {
            state.activeStreamedTrackerIds + persistedStreamTargetIds
        }
        // STREAMING EXCLUSION (resume): just normalize the persisted ids. The projector / runtime
        // resync re-applies the only meaningful exclusion (locally-recorded) on the next reconcile
        // pass; pre-filtering the selected tracker here would silently drop our own tracker from a
        // persisted group / all-queue stream every time we resume from background.
        val resumeStreamTrackerIds = StreamingTargetPolicy.normalizeTrackerIds(unsanitizedResumeStreamTrackerIds)
        val outcome = reopenOrchestrator.resolve(
            TrackerMapResumeInput(
                trackingRunning = state.runtime.localRecordingActive,
                mapReady = mapReady,
                showAllTrackers = state.mode == TrackerMapDisplayMode.ALL_QUEUE,
                mapViewContext = if (state.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
                    TrackerMapViewContext.GROUP
                } else {
                    TrackerMapViewContext.SINGLE_TRACKER
                },
                activeStreamedTrackerIds = resumeStreamTrackerIds,
                currentGroupTrackIds = if (state.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
                    groupSelection.trackerIds
                } else {
                    emptySet()
                },
                selectedTrackerId = selectedTrackerId,
                displayedTrackerId = TrackerMapDisplayIds.effectiveDisplayedTrackerId(state),
                hasTrailPoints = state.trail.isNotEmpty(),
                hasPendingInitialTracker = hasPendingInitialTracker,
                backgroundedDurationMs = backgroundDurationMs
            )
        )
        outcome.invariants
            .filter { !it.satisfied }
            .forEach { invariant ->
                GeoVaultCaptureLog.w(TrackerMapViewModel.TAG, "map_update Reopen invariant violation ${invariant.invariant}: ${invariant.details}")
            }
        rt.ports.viewModelScope.launch {
            applyReopenDecision(outcome.decision)
            rt.streamRosterResolver.refreshStreamTargets()
            rt.streamTargetReconciler.bumpReconcileToken()
            lastBackgroundAtElapsedMs = 0L
            pendingResumeEvaluation = false
        }
    }

    internal fun streamingActiveTargetsMatchDisplayed(
        state: TrackerMapUiState,
        streamRuntime: LiveStreamSubscriptionState,
        groupSelection: TrackerMapGroupModeSelection,
    ): Boolean {
        return TrackerMapViewModel.streamingActiveTargetsMatchDisplayed(
            mode = state.mode,
            displayedIds = when (state.mode) {
                TrackerMapDisplayMode.GROUP_PLACEHOLDER -> groupSelection.trackerIds
                TrackerMapDisplayMode.ALL_QUEUE -> rt.visibleMapRosterTrackerIds()
                else -> emptySet()
            },
            localRecordingActive = state.runtime.localRecordingActive,
            locallyRecordedTrackerId = state.runtime.locallyRecordedTrackerId,
            activeStreamTargets = streamRuntime.activeTargets,
        )
    }

    internal fun displayedRosterHasServerHistory(
        state: TrackerMapUiState,
        groupSelection: TrackerMapGroupModeSelection,
    ): Boolean {
        val rosterIds = when (state.mode) {
            TrackerMapDisplayMode.GROUP_PLACEHOLDER -> groupSelection.trackerIds
            TrackerMapDisplayMode.ALL_QUEUE -> rt.visibleMapRosterTrackerIds()
            else -> emptySet()
        }
        val normalizedRosterIds = rosterIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (normalizedRosterIds.isEmpty()) return false
        val trackers = rt.dependencies.trackerManagementStateStore.trackers.value
        val snapshots = rt.dependencies.historyRepository.snapshots.value
        return normalizedRosterIds.all { trackerId ->
            TrackerMapHistoryUiSync.hasAuthoritativeServerTrunk(snapshots, trackers, trackerId)
        }
    }

    private suspend fun applyReopenDecision(decision: TrackerMapResumeDecision) {
        when (decision) {
            TrackerMapResumeDecision.NoOp -> Unit
            TrackerMapResumeDecision.MultiContextNoStreaming -> Unit
            is TrackerMapResumeDecision.StartMultiContextStreaming -> {
                pendingReopenSingleTrackerLoadId = null
                val locallyRecordedTrackerId = rt.stateHub.uiStateMutable.value.runtime.locallyRecordedTrackerId
                val ids = StreamingTargetPolicy.remoteSubscriptionTargets(
                    StreamingTargetPolicyInput(
                        requestedTrackerIds = decision.trackerIds,
                        locallyRecordedTrackerIds = rt.reload.setOfNotBlank(locallyRecordedTrackerId),
                    )
                )
                rt.stateHub.uiStateMutable.update { cur ->
                    cur.copy(
                        streamTargetIds = ids,
                        remoteLastPoints = TrackerMapViewModel.filterRemoteLastPointsForAcceptedIds(cur.remoteLastPoints, ids),
                    )
                }
                rt.streamTargetReconciler.bumpReconcileToken()
            }
            TrackerMapResumeDecision.ClearSingleTrackerState -> {
                pendingReopenSingleTrackerLoadId = null
                // CONTEXT RESET: no tracker is displayed anymore, so any previously-set selection
                // lock is no longer meaningful — clear all map locks alongside the card.
                rt.stateHub.uiStateMutable.update { cur ->
                    cur.copy(
                        displayedTrackerId = "",
                        displayedTrackerName = "",
                        remoteLastPoints = emptyMap(),
                        streamTargetIds = emptySet(),
                    ).withAllMapLocksDisabled().withClearedMapSelectionCard()
                }
                rt.dependencies.streamingReconciler.stopForegroundStreaming()
            }
            is TrackerMapResumeDecision.LoadSingleTrackerRuntime,
            is TrackerMapResumeDecision.LoadSingleTrackerBootstrap -> {
                val trackerId = when (decision) {
                    is TrackerMapResumeDecision.LoadSingleTrackerRuntime -> decision.trackerId
                    is TrackerMapResumeDecision.LoadSingleTrackerBootstrap -> decision.trackerId
                }
                pendingReopenSingleTrackerLoadId = trackerId.takeIf { it.isNotBlank() }
                if (trackerId.isNotBlank()) {
                    val runtime = rt.stateHub.uiStateMutable.value.runtime
                    val trackerName = if (trackerId == runtime.selectedTrackerId) {
                        runtime.selectedTrackerName
                    } else {
                        rt.stateHub.uiStateMutable.value.displayedTrackerName
                    }
                    // SWITCHING DISPLAYED TRACKER: when the resume decision points us at a
                    // different tracker than the one currently displayed, drop any selection lock
                    // tied to the previous tracker. Same-tracker bootstraps/runtime resyncs leave
                    // the lock alone so a user-set lock survives a benign resume.
                    val previousDisplayedTrackerId = rt.stateHub.uiStateMutable.value.displayedTrackerId.trim()
                    val trackerChanged = trackerId.trim() != previousDisplayedTrackerId
                    rt.stateHub.uiStateMutable.value = rt.stateHub.uiStateMutable.value.copy(
                        displayedTrackerId = trackerId,
                        displayedTrackerName = trackerName,
                    ).let { next ->
                        if (trackerChanged) next.withAllMapLocksDisabled() else next
                    }.withClearedMapSelectionCard()
                }
                rt.reload.requestAndAwaitRuntimeTrailReload(TrackerMapTrailReloadReason.ExplicitTrackerLoad)
                if (pendingReopenSingleTrackerLoadId == trackerId) {
                    pendingReopenSingleTrackerLoadId = null
                }
                rt.streamTargetReconciler.bumpReconcileToken()
            }
            TrackerMapResumeDecision.RestartDisplayedTrackerStreaming -> {
                pendingReopenSingleTrackerLoadId = null
                rt.streamTargetReconciler.bumpReconcileToken()
            }
        }
    }

    // --- Map lock mutation entry points -----------------------------------------------------
    // Together with `toggleTrackerLock` above, these are the only places [TrackerMapUiState]'s
    // three lock fields are ever written outside of an auto-lock ([TrackerMapAutoLockPolicy]) or
    // a full context reset. `setFollowLock` and `disableAllMapLocks` always start from
    // [withAllMapLocksDisabled] -- only `setLiveActiveFit` composes with an existing lock instead
    // of replacing it, per [TrackerMapLiveActiveFitPolicy.composesWithSelectionLock].

    /** SINGLE_SESSION-with-nothing-displayed primary FAB: follows the device's own GPS position. */
    internal fun setFollowLock(enabled: Boolean) {
        val state = rt.stateHub.uiStateMutable.value
        rt.stateHub.uiStateMutable.value = if (enabled) {
            state.withAllMapLocksDisabled().copy(followLockEnabled = true)
        } else {
            state.copy(followLockEnabled = false)
        }
    }

    /**
     * User zoomed without taking over the camera. Lock flags stay; live-active-fit recenters at
     * the current zoom.
     */
    internal fun onUserOwnedZoom() {
        rt.cameraCoordinator.onUserOwnedZoom()
        rt.ports.viewModelScope.launch { rt.display.publishRenderPackage() }
    }

    /** A manual pan / fling / rotate always releases every lock, regardless of which is active. */
    internal fun disableAllMapLocks() {
        // GESTURE-BUMP: bump the coordinator generation unconditionally, regardless of whether a
        // lock was actually active -- a directive minted a moment ago (e.g. a reload-landing fit
        // still in flight) must never apply after the user has started manually moving the
        // camera, even if no lock flag happened to be set at the time.
        rt.cameraCoordinator.onUserGestureStarted()
        val state = rt.stateHub.uiStateMutable.value
        if (!state.hasAnyMapLockActive()) {
            return
        }
        rt.stateHub.uiStateMutable.value = state.withAllMapLocksDisabled()
    }

    /** Secondary FAB in SINGLE_SESSION (and the primary FAB in ALL_QUEUE/GROUP_PLACEHOLDER). */
    internal fun setLiveActiveFit(enabled: Boolean) {
        val state = rt.stateHub.uiStateMutable.value
        rt.stateHub.uiStateMutable.value = if (enabled) {
            if (TrackerMapLiveActiveFitPolicy.composesWithSelectionLock(state.mode)) {
                // Preserve the existing selection lock rather than clearing it via
                // withAllMapLocksDisabled() -- see TrackerMapLiveActiveFitPolicy's class doc for
                // why these two compose here instead of being mutually exclusive.
                state.copy(followLockEnabled = false, liveActiveFitEnabled = true)
            } else {
                state.withAllMapLocksDisabled().copy(liveActiveFitEnabled = true)
            }
        } else {
            state.copy(liveActiveFitEnabled = false)
        }
        if (enabled) {
            rt.ports.viewModelScope.launch { rt.display.publishRenderPackage() }
            requestFitTrail()
        }
    }

    internal fun requestFitTrail(mode: TrackerMapFitTrailMode = TrackerMapFitTrailMode.Animated) {
        // FIT-FRESHNESS: bounds are computed synchronously right now, against whatever state is
        // current at the moment of the request, rather than lazily inside the Compose consumer.
        // This is strictly fresher than pulling bounds later when the directive is applied.
        rt.cameraCoordinator.requestExplicitFit(rt.display.trailBoundsOrNull(), mode)
    }

    internal fun stateWithRefreshedSelectionCard(
        state: TrackerMapUiState,
        changedTrackerId: String,
    ): TrackerMapUiState {
        val selection = state.selectedMapTracker ?: return state
        if (!state.isBottomCardVisible || selection.trackerId != changedTrackerId.trim()) return state
        val refreshed = buildSelectionCard(rt.display.buildSessionSnapshotForState(state), selection.trackerId) ?: return state
        return state.copy(selectedMapTracker = refreshed)
    }

}
