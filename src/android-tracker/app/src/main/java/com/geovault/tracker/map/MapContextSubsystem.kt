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

internal class MapContextSubsystem(private val rt: TrackerMapRuntime) {
    fun setMode(mode: TrackerMapDisplayMode) {
        val groupOptions = if (mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
            rt.resolveGroupModeOptions()
        } else {
            emptyList()
        }
        val preferredGroupId = if (mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
            val currentGroup = rt.uiStateMutable.value.currentGroupId.trim()
            currentGroup.takeIf { candidate -> groupOptions.any { it.groupId == candidate } }
                ?: groupOptions.firstOrNull()?.groupId.orEmpty()
        } else {
            ""
        }
        val nextState = rt.uiStateMutable.value.copy(
            mode = mode,
            currentGroupId = preferredGroupId,
            groupModeOptions = groupOptions,
        )
        val pendingReopenTrackerId = if (mode == TrackerMapDisplayMode.SINGLE_SESSION) {
            rt.pendingReopenSingleTrackerLoadId
        } else {
            null
        }
        applyMapContextTransition(
            nextState = nextState,
            pendingReopenTrackerId = pendingReopenTrackerId,
            reloadReason = TrackerMapTrailReloadReason.MapContextChange,
        )
    }

    fun setGroupModeGroup(groupId: String) {
        val normalized = groupId.trim()
        if (normalized.isEmpty()) return
        val state = rt.uiStateMutable.value
        if (state.currentGroupId == normalized && state.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
            return
        }
        val nextState = state.copy(
            currentGroupId = normalized,
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
        )
        applyMapContextTransition(
            nextState = nextState,
            pendingReopenTrackerId = null,
            reloadReason = TrackerMapTrailReloadReason.MapContextChange,
        )
    }

    fun openTrackerOnMap(trackerId: String, trackerName: String?) {
        val normalizedId = trackerId.trim()
        if (normalizedId.isEmpty()) return
        val state = rt.uiStateMutable.value
        val resolvedName = trackerName?.trim().orEmpty().ifBlank {
            if (normalizedId == state.runtime.selectedTrackerId) {
                state.runtime.selectedTrackerName
            } else {
                rt.trackerManagementStateStore.trackers.value
                    .firstOrNull { it.id == normalizedId }
                    ?.name
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: state.displayedTrackerName.takeIf { state.displayedTrackerId == normalizedId }
                    ?: normalizedId
            }
        }
        val nextState = state.copy(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = normalizedId,
            displayedTrackerName = resolvedName,
            currentGroupId = "",
            groupModeOptions = emptyList(),
        )
        applyMapContextTransition(
            nextState = nextState,
            pendingReopenTrackerId = normalizedId,
            reloadReason = TrackerMapTrailReloadReason.ExplicitTrackerLoad,
        )
    }

    fun openGroupOnMap(groupId: String) {
        val normalizedId = groupId.trim()
        if (normalizedId.isEmpty()) return
        val groupOptions = rt.resolveGroupModeOptions()
        val resolvedGroupId = normalizedId.takeIf { candidate ->
            groupOptions.any { it.groupId == candidate }
        } ?: groupOptions.firstOrNull()?.groupId.orEmpty()
        val nextState = rt.uiStateMutable.value.copy(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            currentGroupId = resolvedGroupId,
            groupModeOptions = groupOptions,
        )
        applyMapContextTransition(
            nextState = nextState,
            pendingReopenTrackerId = null,
            reloadReason = TrackerMapTrailReloadReason.MapContextChange,
        )
    }

    fun restoreSelectedTrackerAfterStreamingStop() {
        restoreSelectedTrackerMapContext()
    }

    fun restoreSelectedTrackerMapContext() {
        val state = rt.uiStateMutable.value
        val selectedId = state.runtime.selectedTrackerId.trim()
        rt.streamingReconciler.stopForegroundStreaming()
        // CHIP-X / POST-STREAM RESTORE: always collapse back to SINGLE_SESSION on the selected
        // tracker. Both entry points (the X on the top-left chip and the auto-restore that fires
        // when streaming ends) want a deterministic return to the user's selected tracker view.
        // Leaving the mode unchanged would only stop the foreground service and then immediately
        // reissue Start via the combined reconcile flow (since GROUP_PLACEHOLDER's
        // streamTargetIds are unchanged), producing a visible "reconnecting" flicker without
        // any actual exit from the group.
        if (selectedId.isBlank()) {
            rt.pendingInitialTrackerForMap = true
            rt.pendingResumeEvaluation = true
            return
        }
        val nextState = state.copy(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = selectedId,
            displayedTrackerName = state.runtime.selectedTrackerName,
            currentGroupId = "",
            groupModeOptions = emptyList(),
        )
        applyMapContextTransition(
            nextState = nextState,
            pendingReopenTrackerId = selectedId,
            reloadReason = TrackerMapTrailReloadReason.RestoreSelectedAfterStreaming,
        )
    }

    fun resolveListNavigationTarget(preferredTrackerIdOverride: String? = null): MapListNavigationTarget {
        val state = rt.uiStateMutable.value
        val preferredTrackerId = preferredTrackerIdOverride?.trim().orEmpty().ifBlank {
            TrackerMapDisplayIds.effectiveDisplayedTrackerId(state)
        }.ifBlank {
            state.runtime.selectedTrackerId.trim()
        }.ifBlank { "" }
        val preferredTrackerOwned = rt.trackerManagementStateStore.trackers.value
            .firstOrNull { it.id == preferredTrackerId }
            ?.isOwner()
        val currentGroupOwned = rt.trackerManagementStateStore.groups.value
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

    fun onTrackerMarkerTapped(trackerId: String) {
        val normalizedTrackerId = trackerId.trim()
        if (normalizedTrackerId.isEmpty()) return
        val snapshot = rt.display.buildCurrentSessionSnapshot()
        val state = snapshot.uiState
        val selection = buildSelectionCard(snapshot, normalizedTrackerId)
        if (selection == null) {
            rt.uiStateMutable.value = state.withClearedMapSelectionCard()
            return
        }
        rt.uiStateMutable.value = stateWithSelectionCard(state, selection)
    }

    fun onMapBackgroundTapped(): Boolean {
        val state = rt.uiStateMutable.value
        if (!TrackerMapViewModel.resolveBackgroundTapShouldCloseBottomCard(
                isBottomCardVisible = state.isBottomCardVisible,
                hasSelectionCard = state.selectedMapTracker != null
            )
        ) {
            return false
        }
        rt.uiStateMutable.value = state.withClearedMapSelectionCard()
        return true
    }

    fun selectMapTrackerFromTap(trackerId: String) {
        onTrackerMarkerTapped(trackerId)
    }

    fun clearMapTrackerSelection() {
        onMapBackgroundTapped()
    }

    fun focusSelectedTrackerOnMap() {
        val state = rt.uiStateMutable.value
        val selection = state.selectedMapTracker ?: return
        openTrackerOnMap(selection.trackerId, selection.trackerName)
    }

    fun toggleSelectedTrackerLock() {
        val state = rt.uiStateMutable.value
        val selection = state.selectedMapTracker ?: return
        toggleTrackerLock(selection.trackerId)
    }

    fun toggleDisplayedTrackerLock() {
        val state = rt.uiStateMutable.value
        val displayedId = TrackerMapDisplayIds.effectiveDisplayedTrackerId(state)
        if (displayedId.isEmpty()) return
        toggleTrackerLock(displayedId)
    }

    internal fun toggleTrackerLock(trackerId: String) {
        val selectedId = trackerId.trim()
        if (selectedId.isEmpty()) return
        val state = rt.uiStateMutable.value
        val nextSelectionLock = if (state.selectionLockTrackerId == selectedId) "" else selectedId
        rt.uiStateMutable.value = state.withAllMapLocksDisabled().copy(selectionLockTrackerId = nextSelectionLock)
    }

    fun selectionLockPointOrNull(): Pair<Double, Double>? {
        return selectionLockPointOrNull(rt.display.buildCurrentSessionSnapshot())
    }

    internal fun selectionLockPointOrNull(
        snapshot: TrackerMapSessionSnapshot
    ): Pair<Double, Double>? {
        val state = snapshot.uiState
        val trackerId = state.selectionLockTrackerId.trim()
        if (trackerId.isEmpty()) return null
        snapshot.tracks[trackerId]?.renderTrail?.lastOrNull()?.let { point ->
            return point.latitude to point.longitude
        }
        snapshot.acceptedRemoteLastPoints[trackerId]?.let { point ->
            return point.lat to point.lon
        }
        val point = resolveTrackerPointData(snapshot, trackerId) ?: return null
        return point.latitude to point.longitude
    }

    internal fun buildSelectionCard(
        snapshot: TrackerMapSessionSnapshot,
        trackerId: String
    ): TrackerMapSelectionCard? {
        val state = snapshot.uiState
        val tracker = rt.trackerManagementStateStore.trackers.value.firstOrNull { it.id == trackerId }
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

    internal fun resolveTrackerPointData(
        snapshot: TrackerMapSessionSnapshot,
        trackerId: String
    ): TrackerMapResolvedPoint? {
        val normalizedId = trackerId.trim()
        if (normalizedId.isEmpty()) return null
        val tracker = rt.trackerManagementStateStore.trackers.value.firstOrNull { it.id == normalizedId }
        val effectiveState = snapshot.uiState.copy(
            trail = snapshot.singleTrail,
            allQueueTrailsByTracker = snapshot.renderTrailsByTracker,
            remoteLastPoints = snapshot.acceptedRemoteLastPoints,
        )
        return TrackerMapLastPointResolver.resolveRenderedMarkerPoint(
            state = effectiveState,
            trackerId = trackerId,
            tracker = tracker,
            acceptedRemoteTrackerIds = snapshot.plan.acceptedRemoteTrackerIds,
        )
    }

    internal fun stateWithSelectionCard(
        state: TrackerMapUiState,
        selection: TrackerMapSelectionCard
    ): TrackerMapUiState {
        val nextSelectionLockId = state.selectionLockTrackerId.trim()
            .takeIf { it.isNotEmpty() && it == selection.trackerId }
            .orEmpty()
        return state.copy(
            isBottomCardVisible = TrackerMapViewModel.resolveBottomCardVisibilityForMarkerTap(hasSelectionCard = true),
            selectedMapTracker = selection,
            selectionLockTrackerId = nextSelectionLockId,
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

    internal fun clearRenderedTrailsAfterHistoryCleared(trackerId: String) {
        rt.uiStateMutable.value = stateWithClearedRenderedTrails(rt.uiStateMutable.value, trackerId)
        rt.lastTrailLoadSeed = null
    }

    internal fun stateWithResetMapContext(
        state: TrackerMapUiState,
        preservedSingleTrackerId: String? = null,
    ): TrackerMapUiState {
        return TrackerMapContextResetPolicy.reset(
            TrackerMapContextResetInput(
                state = state,
                preservedSingleTrackerId = preservedSingleTrackerId,
            )
        )
            .withAllMapLocksDisabled()
            .withClearedMapSelectionCard()
    }

    internal fun applyMapContextTransition(
        nextState: TrackerMapUiState,
        pendingReopenTrackerId: String?,
        reloadReason: TrackerMapTrailReloadReason = TrackerMapTrailReloadReason.GenericMapRefresh,
    ) {
        val preservedSingleTrackerId = if (reloadReason == TrackerMapTrailReloadReason.RestoreSelectedAfterStreaming) {
            pendingReopenTrackerId
        } else {
            null
        }
        rt.uiStateMutable.value = stateWithResetMapContext(
            state = nextState,
            preservedSingleTrackerId = preservedSingleTrackerId,
        )
        rt.display.reprojectTrailsFromRepository("map_context_transition")
        rt.pendingReopenSingleTrackerLoadId = pendingReopenTrackerId
        rt.pendingFitAfterReload = true
        rt.lastTrailLoadSeed = null
        rt.reload.requestRuntimeTrailReload(reloadReason)
        rt.streamRosterResolver.refreshStreamTargets()
    }

    fun onHostPaused() {
        rt.lastBackgroundAtElapsedMs = SystemClock.elapsedRealtime()
    }

    fun onHostResumed() {
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
        if (rt.lastBackgroundAtElapsedMs <= 0L || !rt.mapSurfaceVisible) return
        if (!rt.mapReady) {
            rt.pendingResumeEvaluation = true
            return
        }
        evaluateResumeAfterBackground(allowZeroGap = false)
    }

    fun onMapSurfaceVisible() {
        rt.mapSurfaceVisible = true
        if (!rt.mapReady) {
            rt.pendingResumeEvaluation = rt.pendingResumeEvaluation ||
                rt.pendingInitialTrackerForMap ||
                rt.lastBackgroundAtElapsedMs > 0L
            return
        }
        evaluateResumeAfterBackground(allowZeroGap = rt.pendingInitialTrackerForMap || rt.pendingResumeEvaluation)
        rt.display.reprojectTrailsFromRepository("map_surface_visible")
        rt.streamTargetReconciler.bumpReconcileToken()
    }

    fun onMapSurfaceHidden(markBackground: Boolean = false) {
        rt.mapSurfaceVisible = false
        rt.mapReady = false
        if (markBackground) {
            rt.lastBackgroundAtElapsedMs = SystemClock.elapsedRealtime()
            rt.pendingResumeEvaluation = true
        }
        rt.streamTargetReconciler.bumpReconcileToken()
        rt.ports.viewModelScope.launch {
            rt.sessionRequestDeduper.clear()
        }
    }

    fun setMapReady(isReady: Boolean) {
        rt.mapReady = isReady
        if (!rt.mapReady || !rt.pendingResumeEvaluation) return
        rt.pendingResumeEvaluation = false
        evaluateResumeAfterBackground(allowZeroGap = rt.pendingInitialTrackerForMap)
    }

    internal fun evaluateResumeAfterBackground(allowZeroGap: Boolean) {
        val backgroundDurationMs = if (rt.lastBackgroundAtElapsedMs > 0L) {
            SystemClock.elapsedRealtime() - rt.lastBackgroundAtElapsedMs
        } else {
            0L
        }
        if (backgroundDurationMs <= 0L && !allowZeroGap) return
        val state = rt.uiStateMutable.value
        val groupSelection = rt.resolveGroupModeSelection(state)
        val hasPendingInitialTracker = rt.pendingInitialTrackerForMap
        val selectedTrackerId = state.runtime.selectedTrackerId.trim()
        if (hasPendingInitialTracker &&
            state.mode == TrackerMapDisplayMode.SINGLE_SESSION &&
            selectedTrackerId.isBlank() &&
            TrackerMapDisplayIds.effectiveDisplayedTrackerId(state).isBlank()
        ) {
            rt.pendingResumeEvaluation = true
            return
        }
        rt.pendingInitialTrackerForMap = false
        val streamRuntime = rt.liveStreamSubscriptionRepository.state.value
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
            rt.lastBackgroundAtElapsedMs = 0L
            rt.pendingResumeEvaluation = false
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
        val outcome = rt.reopenOrchestrator.resolve(
            TrackerMapResumeInput(
                trackingRunning = state.runtime.localRecordingActive,
                mapReady = rt.mapReady,
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
                GeoVaultCaptureLog.w(TrackerMapViewModel.TAG, "Reopen invariant violation ${invariant.invariant}: ${invariant.details}")
            }
        rt.ports.viewModelScope.launch {
            applyReopenDecision(outcome.decision)
            rt.streamRosterResolver.refreshStreamTargets()
            rt.streamTargetReconciler.bumpReconcileToken()
            rt.lastBackgroundAtElapsedMs = 0L
            rt.pendingResumeEvaluation = false
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
        val trackers = rt.trackerManagementStateStore.trackers.value
        val snapshots = rt.historyRepository.snapshots.value
        return normalizedRosterIds.all { trackerId ->
            TrackerMapHistoryUiSync.hasAuthoritativeServerTrunk(snapshots, trackers, trackerId)
        }
    }

    internal suspend fun applyReopenDecision(decision: TrackerMapResumeDecision) {
        when (decision) {
            TrackerMapResumeDecision.NoOp -> Unit
            TrackerMapResumeDecision.MultiContextNoStreaming -> Unit
            is TrackerMapResumeDecision.StartMultiContextStreaming -> {
                rt.pendingReopenSingleTrackerLoadId = null
                val locallyRecordedTrackerId = rt.uiStateMutable.value.runtime.locallyRecordedTrackerId
                val ids = StreamingTargetPolicy.remoteSubscriptionTargets(
                    StreamingTargetPolicyInput(
                        requestedTrackerIds = decision.trackerIds,
                        locallyRecordedTrackerIds = rt.reload.setOfNotBlank(locallyRecordedTrackerId),
                    )
                )
                rt.uiStateMutable.update { cur ->
                    cur.copy(
                        streamTargetIds = ids,
                        remoteLastPoints = TrackerMapViewModel.filterRemoteLastPointsForAcceptedIds(cur.remoteLastPoints, ids),
                    )
                }
                rt.streamTargetReconciler.bumpReconcileToken()
            }
            TrackerMapResumeDecision.ClearSingleTrackerState -> {
                rt.pendingReopenSingleTrackerLoadId = null
                // CONTEXT RESET: no tracker is displayed anymore, so any previously-set selection
                // lock is no longer meaningful — clear all map locks alongside the card.
                rt.uiStateMutable.update { cur ->
                    cur.copy(
                        displayedTrackerId = "",
                        displayedTrackerName = "",
                        remoteLastPoints = emptyMap(),
                        streamTargetIds = emptySet(),
                    ).withAllMapLocksDisabled().withClearedMapSelectionCard()
                }
                rt.streamingReconciler.stopForegroundStreaming()
            }
            is TrackerMapResumeDecision.LoadSingleTrackerRuntime,
            is TrackerMapResumeDecision.LoadSingleTrackerBootstrap -> {
                val trackerId = when (decision) {
                    is TrackerMapResumeDecision.LoadSingleTrackerRuntime -> decision.trackerId
                    is TrackerMapResumeDecision.LoadSingleTrackerBootstrap -> decision.trackerId
                }
                rt.pendingReopenSingleTrackerLoadId = trackerId.takeIf { it.isNotBlank() }
                if (trackerId.isNotBlank()) {
                    val runtime = rt.uiStateMutable.value.runtime
                    val trackerName = if (trackerId == runtime.selectedTrackerId) {
                        runtime.selectedTrackerName
                    } else {
                        rt.uiStateMutable.value.displayedTrackerName
                    }
                    // SWITCHING DISPLAYED TRACKER: when the resume decision points us at a
                    // different tracker than the one currently displayed, drop any selection lock
                    // tied to the previous tracker. Same-tracker bootstraps/runtime resyncs leave
                    // the lock alone so a user-set lock survives a benign resume.
                    val previousDisplayedTrackerId = rt.uiStateMutable.value.displayedTrackerId.trim()
                    val trackerChanged = trackerId.trim() != previousDisplayedTrackerId
                    rt.uiStateMutable.value = rt.uiStateMutable.value.copy(
                        displayedTrackerId = trackerId,
                        displayedTrackerName = trackerName,
                    ).let { next ->
                        if (trackerChanged) next.withAllMapLocksDisabled() else next
                    }.withClearedMapSelectionCard()
                }
                rt.reload.requestAndAwaitRuntimeTrailReload(TrackerMapTrailReloadReason.ExplicitTrackerLoad)
                if (rt.pendingReopenSingleTrackerLoadId == trackerId) {
                    rt.pendingReopenSingleTrackerLoadId = null
                }
                rt.streamTargetReconciler.bumpReconcileToken()
            }
            TrackerMapResumeDecision.RestartDisplayedTrackerStreaming -> {
                rt.pendingReopenSingleTrackerLoadId = null
                rt.streamTargetReconciler.bumpReconcileToken()
            }
        }
    }

    fun setFollowLock(enabled: Boolean) {
        val state = rt.uiStateMutable.value
        rt.uiStateMutable.value = if (enabled) {
            state.withAllMapLocksDisabled().copy(followLockEnabled = true)
        } else {
            state.copy(followLockEnabled = false)
        }
    }

    fun disableAllMapLocks() {
        val state = rt.uiStateMutable.value
        if (!state.followLockEnabled && !state.liveActiveFitEnabled && state.selectionLockTrackerId.isEmpty()) {
            return
        }
        rt.uiStateMutable.value = state.withAllMapLocksDisabled()
    }

    fun setLiveActiveFit(enabled: Boolean) {
        val state = rt.uiStateMutable.value
        rt.uiStateMutable.value = if (enabled) {
            state.withAllMapLocksDisabled().copy(liveActiveFitEnabled = true)
        } else {
            state.copy(liveActiveFitEnabled = false)
        }
        if (enabled) {
            rt.ports.viewModelScope.launch { rt.display.publishRenderPackage() }
            requestFitTrail()
        }
    }

    fun requestFitTrail(mode: TrackerMapFitTrailMode = TrackerMapFitTrailMode.Animated) {
        rt.fitTrailSignal.trySend(mode)
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
