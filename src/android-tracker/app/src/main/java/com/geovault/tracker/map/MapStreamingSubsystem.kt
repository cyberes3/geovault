package com.geovault.tracker.map

import com.geovault.common.logging.CaptureLogThrottle
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.MapStreamingServiceHelper
import com.geovault.tracker.SelectedTrackerManager
import com.geovault.tracker.location.TrackingLifecycleState
import com.geovault.tracker.policy.StreamingTargetPolicy
import com.geovault.tracker.policy.TrackPointBus
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.presentation.*
import com.geovault.tracker.services.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Job

internal class MapStreamingSubsystem(private val rt: TrackerMapRuntime) {
        fun startCollectors() {
                    // PRELOAD-AT-INIT: read the persisted selected tracker id straight from prefs and
                // seed `rt.uiStateMutable.trail` from the local Room queue ASAP. This races (intentionally)
                // with the first `rt.uiStateMutable.collect` below so the very first render package the map
                // sees already has a trail to fit the camera to. Without this, the launch sequence
                // is "empty render -> 0,0 flash -> ExplicitTrackerLoad -> server fetch -> snap"
                // because `getTrackers()` is metadata-only (no geometry) so the in-memory cache
                // preload inside `reloadTrailFromDatabaseLocked` returns null on every cold launch.
                // The Room queue is the only source of truth that survives process death without
                // a network round-trip.
                rt.ports.viewModelScope.launch {
                    rt.reload.seedInitialTrailFromLocalQueue()
                }
                rt.ports.viewModelScope.launch {
                    combine(rt.uiStateMutable, rt.historyRepository.snapshots) { state, _ -> state }
                        .collect {
                            rt.display.publishRenderPackage()
                        }
                }
                rt.ports.viewModelScope.launch {
                    rt.trackerSettingsRepository.observeSettings()
                        .map { it.groupModeFitOnlyActiveTrackers }
                        .distinctUntilChanged()
                        .drop(1)
                        .collect {
                            rt.display.publishRenderPackage()
                        }
                }
                rt.ports.viewModelScope.launch {
                    TrackingRuntimeStateStore.state.collect { snap ->
                        val effectiveLifecycleState = if (!snap.isRunning && snap.startupActive) {
                            TrackingLifecycleState.STARTING
                        } else {
                            snap.lifecycleState
                        }
                        val effectiveRuntime = snap.copy(
                            isRunning = snap.isRunning,
                            lifecycleState = effectiveLifecycleState
                        )
                        val current = rt.uiStateMutable.value
                        val displayedTrackerId = if (current.displayedTrackerId.isBlank()) {
                            effectiveRuntime.selectedTrackerId
                        } else {
                            current.displayedTrackerId
                        }
                        val displayedTrackerName = if (current.displayedTrackerName.isBlank()) {
                            effectiveRuntime.selectedTrackerName
                        } else {
                            current.displayedTrackerName
                        }
                        rt.uiStateMutable.value = current.copy(
                            runtime = effectiveRuntime,
                            displayedTrackerId = displayedTrackerId,
                            displayedTrackerName = displayedTrackerName
                        )
                        val runtimeSnapshotSignature =
                            "mode=${current.mode}|selected=${snap.selectedTrackerId.trim()}|local=${snap.localRecordingActive}|" +
                                "trail=${current.trail.size}|multi=${current.allQueueTrailsByTracker.mapSizes()}|" +
                                "lastTs=${snap.lastTrackedTimestampMs}"
                        if (CaptureLogThrottle.shouldLogOnChange("vm_runtime_snapshot", runtimeSnapshotSignature)) {
                            GeoVaultCaptureLog.d(
                                TrackerMapViewModel.TAG,
                                "map_update vm_runtime_snapshot mode=${current.mode} selected=${snap.selectedTrackerId.trim()} " +
                                    "localActive=${snap.localRecordingActive} localId=${snap.locallyRecordedTrackerId.trim()} " +
                                    "sessionStart=${snap.sessionStartTimeMs} lastTs=${snap.lastTrackedTimestampMs} " +
                                    "lat=${snap.lastTrackedLatitude} lon=${snap.lastTrackedLongitude} " +
                                    "displayed=$displayedTrackerId trail=${current.trail.size} multi=${current.allQueueTrailsByTracker.mapSizes()}"
                            )
                        }
                        val prevLocalRecording = rt.lastObservedLocalRecordingActive
                        rt.lastObservedLocalRecordingActive = snap.localRecordingActive
                        if (prevLocalRecording != null && !prevLocalRecording && snap.localRecordingActive) {
                            val afterRuntime = rt.uiStateMutable.value
                            when (
                                val autoLock = TrackerMapAutoLockPolicy.resolveAutoLockOnRecordingStart(
                                    mode = afterRuntime.mode,
                                    displayedTrackerId = afterRuntime.displayedTrackerId,
                                    selectedTrackerId = afterRuntime.runtime.selectedTrackerId,
                                )
                            ) {
                                is TrackerMapAutoLockOnRecordingResult.SelectionLock -> {
                                    rt.uiStateMutable.update {
                                        it.withAllMapLocksDisabled().copy(selectionLockTrackerId = autoLock.trackerId)
                                    }
                                }
                                TrackerMapAutoLockOnRecordingResult.LiveActiveFit -> {
                                    rt.uiStateMutable.update {
                                        it.withAllMapLocksDisabled().copy(liveActiveFitEnabled = true)
                                    }
                                    rt.display.publishRenderPackage()
                                    rt.context.requestFitTrail()
                                }
                                TrackerMapAutoLockOnRecordingResult.None -> Unit
                            }
                        }
                        val runtimeResyncDecision = rt.runtimeResyncPolicy.decide(
                            previousIsRunning = rt.lastObservedTrackingRunning,
                            currentIsRunning = snap.isRunning,
                            mapReady = rt.mapReady,
                            mapViewContext = if (current.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
                                TrackerMapViewContext.GROUP
                            } else {
                                TrackerMapViewContext.SINGLE_TRACKER
                            }
                        )
                        rt.lastObservedTrackingRunning = snap.isRunning
                        // RECORDING-DELTA RELOAD: a localRecordingActive transition flips the
                        // streaming exclusion (locally-recorded id is added/removed from
                        // remoteSubscriptionIds) AND the trail merge plan (overlayTrackerId set
                        // becomes non-empty/empty). Both demand a forced server refetch so the
                        // multi-trail's locally-recorded entry is rebuilt with real geometry instead
                        // of relying on `refreshStreamTargets`'s subscription-set delta firing
                        // StreamingStart as a side effect. Cosmetic ticks remain GenericMapRefresh.
                        val recordingTransitioned = prevLocalRecording != null &&
                            prevLocalRecording != snap.localRecordingActive
                        val reloadReason = if (recordingTransitioned) {
                            TrackerMapTrailReloadReason.StreamingStart
                        } else {
                            TrackerMapTrailReloadReason.GenericMapRefresh
                        }
                        val runtimeReloadSignature = buildString {
                            append("running=${snap.isRunning}")
                            append("|local=${snap.localRecordingActive}")
                            append("|selected=${snap.selectedTrackerId.trim()}")
                            append("|sessionStart=${snap.sessionStartTimeMs}")
                            append("|lastTs=${snap.lastTrackedTimestampMs}")
                        }
                        val shouldRequestReload = recordingTransitioned ||
                            runtimeReloadSignature != rt.lastRuntimeTrailReloadSignature
                        if (shouldRequestReload) {
                            rt.lastRuntimeTrailReloadSignature = runtimeReloadSignature
                            if (recordingTransitioned ||
                                CaptureLogThrottle.shouldLogOnChange(
                                    "vm_runtime_reload_request",
                                    "reason=$reloadReason|local=${snap.localRecordingActive}",
                                )
                            ) {
                                GeoVaultCaptureLog.d(
                                    TrackerMapViewModel.TAG,
                                    "map_update vm_runtime_reload_request reason=$reloadReason recordingTransitioned=$recordingTransitioned " +
                                        "prevLocal=$prevLocalRecording currentLocal=${snap.localRecordingActive}"
                                )
                            }
                        }
                        if (recordingTransitioned) {
                            val trackers = rt.trackerManagementStateStore.trackers.value
                            val trackerId = when {
                                snap.localRecordingActive -> snap.locallyRecordedTrackerId.trim()
                                else -> snap.locallyRecordedTrackerId.trim().ifBlank { snap.selectedTrackerId.trim() }
                            }
                            if (trackerId.isNotEmpty()) {
                                if (snap.localRecordingActive) {
                                    rt.historySessionBoundary.onRecordingStarted(
                                        trackerId = trackerId,
                                        trackers = trackers,
                                        sessionStartMs = rt.activeSessionStartMsForRuntime(snap),
                                        repository = rt.historyRepository,
                                    )
                                } else {
                                    rt.historySessionBoundary.onRecordingStopped(
                                        trackerId = trackerId,
                                        trackers = trackers,
                                        dispatcher = rt.historyIntentDispatcher,
                                    )
                                }
                            }
                        }
                        rt.historySessionBoundary.onRuntimeUpdated(
                            runtime = snap,
                            trackers = rt.trackerManagementStateStore.trackers.value,
                            repository = rt.historyRepository,
                        )
                        if (shouldRequestReload) {
                            rt.reload.requestRuntimeTrailReload(reloadReason)
                        }
                        refreshStreamTargets()
                        if (runtimeResyncDecision.restartDisplayedStreaming) {
                            bumpReconcileToken()
                        }
                        if (rt.pendingInitialTrackerForMap && rt.mapReady && rt.mapSurfaceVisible) {
                            rt.context.evaluateResumeAfterBackground(allowZeroGap = true)
                        }
                    }
                }
                rt.ports.viewModelScope.launch {
                    TrackPointBus.events.collect { point ->
                        rt.pointEventChannel.send(point)
                    }
                }
                // COMBINED-RECONCILE: this collector handles _state-mutation_ side effects of streaming
                // runtime updates only (mirroring active ids, trimming remote heads, recomputing the
                // status pill, and emitting post-stop lease cleanup). The reconcile call itself moves to
                // the combined flow below so reconcile inputs come from a single coherent snapshot.
                rt.ports.viewModelScope.launch {
                    LiveStreamRuntimeStateStore.state.collectLatest { snapshot ->
                        val streamSignature =
                            "wants=${snapshot.wantsSubscription}|ended=${snapshot.subscriptionEnded}|health=${snapshot.health}|" +
                                "active=${snapshot.activeTrackerIds.sorted()}|failure=${snapshot.failureReason}"
                        if (CaptureLogThrottle.shouldLogOnChange("vm_stream_snapshot", streamSignature)) {
                            GeoVaultCaptureLog.d(
                                TrackerMapViewModel.TAG,
                                "map_update vm_stream_snapshot wants=${snapshot.wantsSubscription} ended=${snapshot.subscriptionEnded} " +
                                    "health=${snapshot.health} active=${snapshot.activeTrackerIds.sorted()} failure=${snapshot.failureReason}"
                            )
                        }
                        // STREAM-STATE-MACHINE: an active session = the user/app expressed intent AND the
                        // orchestrator hasn't terminated (cleanly stopped or permanently failed). The
                        // active -> ended transition is what triggers lease cleanup below.
                        val sessionActive = snapshot.wantsSubscription && !snapshot.subscriptionEnded
                        val wasActive = rt.lastObservedStreamingSessionActive
                        val hadMapStreamingLease = rt.streamingReconciler.hasMapStreamingLease()
                        rt.lastObservedStreamingSessionActive = sessionActive
                        rt.uiStateMutable.update { current ->
                            val plan = rt.projectSession(
                                state = current.copy(activeStreamedTrackerIds = snapshot.activeTrackerIds),
                                groupSelection = rt.resolveGroupModeSelection(current),
                                visibleRosterTrackerIds = rt.visibleMapRosterTrackerIds(),
                            )
                            current.copy(
                                activeStreamedTrackerIds = snapshot.activeTrackerIds,
                                remoteLastPoints = if (
                                    snapshot.subscriptionEnded &&
                                    current.streamTargetIds.isEmpty()
                                ) {
                                    emptyMap()
                                } else {
                                    TrackerMapViewModel.filterRemoteLastPointsForAcceptedIds(
                                        remoteLastPoints = current.remoteLastPoints,
                                        acceptedRemoteTrackerIds = plan.acceptedRemoteTrackerIds,
                                    )
                                },
                                streamingStatus = TrackerMapStreamingStatusPolicy.resolve(
                                    snapshot = snapshot,
                                    streamTargetIds = current.streamTargetIds,
                                ),
                            )
                        }
                        // STREAM-STATE-MACHINE: lease cleanup keys off subscriptionEnded (Stopped OR
                        // FailedPermanent) rather than only Stopped, so an auth-burned-out streaming
                        // session deterministically falls back to the user's selected tracker instead of
                        // leaving the map staring at the failed group.
                        if ((wasActive || hadMapStreamingLease) &&
                            snapshot.subscriptionEnded &&
                            rt.streamingReconciler.consumeStoppedMapStreamingLease()
                        ) {
                            rt.context.restoreSelectedTrackerMapContext()
                        }
                        // STREAM-FAILURE-INVALIDATE: a fresh failure reason should re-trigger reconcile so
                        // any cleared dedupe in the coordinator can dispatch the next Start cleanly.
                        val failureReason = snapshot.failureReason
                        val previousFailure = rt.lastObservedStreamingFailureReason
                        if (failureReason != null && failureReason != previousFailure) {
                            bumpReconcileToken()
                        }
                        rt.lastObservedStreamingFailureReason = failureReason
                    }
                }
                // FINGERPRINT-DRIVEN REFRESH: pair (previous, current) fingerprints so we can decide
                // whether the change was cosmetic (name/color only — render-only republish) or
                // structural (roster, group membership, per-tracker hidden, map visibility — server
                // refetch). `recent_data_window` is deliberately excluded from both axes and handled
                // by [rt.filterChangeReactor] instead, so a filter edit goes through exactly one path
                // and the cosmetic/structural axes don't double-fire on the same upsert.
                rt.filterChangeReactor.seed(rt.trackerManagementStateStore.trackers.value)
                rt.ports.viewModelScope.launch {
                    combine(
                        rt.trackerManagementStateStore.trackers,
                        rt.trackerManagementStateStore.groups,
                        rt.trackerManagementStateStore.mapVisibility,
                    ) { trackers, groups, visibility ->
                        TrackerMapRenderMetadataFingerprint.from(trackers, groups, visibility)
                    }
                        .distinctUntilChanged()
                        .scan<TrackerMapRenderMetadataFingerprint, Pair<TrackerMapRenderMetadataFingerprint?, TrackerMapRenderMetadataFingerprint?>>(
                            null to null
                        ) { acc, next -> acc.second to next }
                        .drop(1)
                        .collectLatest { (previous, current) ->
                            current ?: return@collectLatest
                            rt.uiStateMutable.value = rt.uiStateMutable.value.copy(renderMetadataSignature = current.combined)
                            val structuralChanged = previous == null || previous.structural != current.structural
                            val reason = if (structuralChanged) {
                                TrackerMapTrailReloadReason.RosterChanged
                            } else {
                                TrackerMapTrailReloadReason.MetadataMapRefresh
                            }
                            rt.reload.requestRuntimeTrailReload(reason)
                            refreshStreamTargets()
                        }
                }
                // Events are discrete, per-tracker side effects (invalidate cache + render +
                // request reload). Using `collect` instead of `collectLatest` here is deliberate:
                // collectLatest cancels the previous handler when a new event lands, which can
                // abort a suspending `rt.sessionRequestDeduper.invalidate(...)` mid-flight. The
                // reactor's `observe` already mutated its baseline synchronously by that point,
                // so a cancelled invalidate would silently leak stale cache entries with no
                // second chance to purge them. Ordered draining keeps the contract simple.
                rt.ports.viewModelScope.launch {
                    rt.trackerManagementStateStore.events.collect { event ->
                        when (event) {
                            is com.geovault.tracker.data.TrackerManagementEvent.HistoryCleared -> {
                                val state = rt.uiStateMutable.value
                                GeoVaultCaptureLog.i(
                                    TrackerMapViewModel.TAG,
                                    "map_update vm_history_cleared_event track=${event.trackerId.trim()} " +
                                        "mode=${state.mode} displayed=${state.displayedTrackerId.trim()} selected=${state.runtime.selectedTrackerId.trim()}"
                                )
                                when (
                                    TrackerMapViewModel.resolveHistoryClearRefreshAction(
                                        mode = state.mode,
                                        displayedTrackerId = state.displayedTrackerId,
                                        selectedTrackerId = state.runtime.selectedTrackerId,
                                        clearedTrackerId = event.trackerId
                                    )
                                ) {
                                    TrackerMapViewModel.HistoryClearRefreshAction.REFRESH_GROUP_OR_ALL,
                                    TrackerMapViewModel.HistoryClearRefreshAction.REFRESH_DISPLAYED_SINGLE,
                                    TrackerMapViewModel.HistoryClearRefreshAction.REFRESH_SELECTED_SINGLE -> {
                                        val clearedTrackerId = event.trackerId.trim()
                                        GeoVaultCaptureLog.i(
                                            TrackerMapViewModel.TAG,
                                            "map_update vm_history_clear_apply track=$clearedTrackerId action=refresh",
                                        )
                                        rt.sessionRequestDeduper.invalidate(event.trackerId)
                                        TrackerMapHistoryUiSync.dispatchHistoryClear(
                                            trackerId = clearedTrackerId,
                                            trackers = rt.trackerManagementStateStore.trackers.value,
                                            dispatcher = rt.historyIntentDispatcher,
                                            activeSessionStartMs = rt.currentActiveSessionStartMs(),
                                        )
                                        rt.uiStateMutable.value = rt.display.applyHistoryTrailsToState(
                                            state = rt.context.stateWithClearedRenderedTrails(rt.uiStateMutable.value, clearedTrackerId),
                                            plan = rt.projectSession(rt.uiStateMutable.value),
                                        )
                                        rt.lastTrailLoadSeed = null
                                        rt.reload.requestRuntimeTrailReload(TrackerMapTrailReloadReason.HistoryCleared)
                                    }
                                    TrackerMapViewModel.HistoryClearRefreshAction.NO_OP -> Unit
                                }
                            }
                            is com.geovault.tracker.data.TrackerManagementEvent.TrackerUpserted -> {
                                handleFilterChange(rt.filterChangeReactor.observe(event.tracker))
                            }
                            is com.geovault.tracker.data.TrackerManagementEvent.TrackersRefreshed -> {
                                val changes = rt.filterChangeReactor.observeAll(event.trackers)
                                for (change in changes) {
                                    handleFilterChange(change)
                                }
                                if (changes.isNotEmpty()) refreshStreamTargets()
                            }
                            else -> Unit
                        }
                    }
                }
                rt.ports.viewModelScope.launch {
                    for (point in rt.pointEventChannel) {
                        rt.trailReloadMutex.withLock {
                            handleTrackPointEvent(point)
                        }
                    }
                }
                // COMBINED-RECONCILE: the single source of truth for reconcile triggering. By combining
                // ui state, streaming runtime, and the explicit invalidation token into one flow we
                // eliminate the dual-collector race where one path could see a fresher uiState than the
                // other saw of streamRuntime (or vice versa). distinctUntilChangedBy on the seed dedupes
                // identical inputs without requiring an internal reconciler-side seed cache.
                rt.ports.viewModelScope.launch {
                    combine(
                        rt.uiStateMutable,
                        LiveStreamRuntimeStateStore.state,
                        rt.reconcileTokenMutable,
                    ) { ui, stream, token -> ReconcileInputs(ui, stream, token) }
                        .distinctUntilChangedBy { reconcileSeedKey(it.state, it.streamRuntime, it.token) }
                        .collect { inputs -> reconcileStreaming(inputs.state, inputs.streamRuntime) }
                }
                refreshStreamTargets()
        }

        internal data class ReconcileInputs(
            val state: TrackerMapUiState,
            val streamRuntime: LiveStreamRuntimeSnapshot,
            val token: Long,
        )

        internal fun bumpReconcileToken() {
            rt.reconcileTokenMutable.value = rt.reconcileTokenMutable.value + 1L
        }

        /**
         * COMBINED-RECONCILE: stable string key that captures every input the reconciler reads. Two
         * adjacent ticks with the same key are deduped; any change here triggers exactly one
         * reconcile call.
         */
        internal fun reconcileSeedKey(
            state: TrackerMapUiState,
            streamRuntime: LiveStreamRuntimeSnapshot,
            token: Long,
        ): String {
            val plan = rt.projectSession(state)
            val streamIdsSignature = state.streamTargetIds.toList().sorted().joinToString(separator = ",")
            val activeIdsSignature = streamRuntime.activeTrackerIds
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .sorted()
                .joinToString(separator = ",")
            val trackingActiveOrStarting = state.runtime.localRecordingActive
            val selectedTrackerId = state.runtime.selectedTrackerId.trim()
            return "${state.mode}|$trackingActiveOrStarting|$streamIdsSignature|${plan.displayedTrackerId}|" +
                "$selectedTrackerId|${plan.displayedTrackerName}|" +
                "${streamRuntime.wantsSubscription}|${streamRuntime.health.name}|$activeIdsSignature|" +
                "${streamRuntime.failureReason.orEmpty()}|$token"
        }

        internal suspend fun handleFilterChange(change: TrackerMapFilterChangeReactor.FilterChange) {
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

        internal fun refreshStreamTargets() {
            val state = rt.uiStateMutable.value
            val groupSelection = rt.resolveGroupModeSelection(state)
            val visibleRosterTrackerIds = rt.visibleMapRosterTrackerIds()
            val plan = rt.projectSession(
                state = state,
                groupSelection = groupSelection,
                visibleRosterTrackerIds = visibleRosterTrackerIds,
            )
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
            val previousStreamTargetIds = state.streamTargetIds
            val nextStreamTargetIds = plan.remoteSubscriptionIds
            val shouldLoadHistoryForStreamingStart = seedChanged &&
                nextStreamTargetIds.isNotEmpty() &&
                nextStreamTargetIds != previousStreamTargetIds
            rt.lastStreamTargetsSeed = seed
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
            if (shouldLoadHistoryForStreamingStart) {
                rt.reload.requestRuntimeTrailReload(TrackerMapTrailReloadReason.StreamingStart)
            }
        }

        internal fun handleTrackPointEvent(point: TrackPointEvent) {
            val state = rt.uiStateMutable.value
            val plan = rt.projectSession(state)
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
            rt.uiStateMutable.update { latest ->
                var next = latest
                val latestPlan = rt.projectSession(latest)
                val latestRoute = TrackerMapPointRouter.route(point, latestPlan)
                if (!latestRoute.accepted) return@update latest

                if (latestRoute.updateRemoteLastPoint) {
                    val remoteTrackerId = latestRoute.normalizedTrackerId
                    next = next.copy(
                        remoteLastPoints = next.remoteLastPoints.toMutableMap().apply {
                            this[remoteTrackerId] = point.copy(trackId = remoteTrackerId)
                        },
                    )
                    shouldUpdate = true
                }

                if (latestRoute.appendSingleTrail || latestRoute.appendMultiTrail) {
                    val overlayCommitted = TrackerMapHistoryUiSync.dispatchLiveOverlay(
                        point = point,
                        trackers = rt.trackerManagementStateStore.trackers.value,
                        dispatcher = rt.historyIntentDispatcher,
                        activeSessionStartMs = rt.activeSessionStartMsForRuntime(latest.runtime),
                    )
                    if (overlayCommitted) {
                        shouldUpdate = true
                    }
                }

                if (!shouldUpdate) return@update latest
                rt.display.applyHistoryTrailsToState(next, latestPlan)
            }
            if (shouldUpdate) {
                val nextState = rt.context.stateWithRefreshedSelectionCard(
                    state = rt.uiStateMutable.value,
                    changedTrackerId = point.trackId,
                )
                rt.uiStateMutable.value = nextState
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

        internal fun reconcileStreaming(
            state: TrackerMapUiState,
            streamRuntime: LiveStreamRuntimeSnapshot = LiveStreamRuntimeStateStore.state.value,
        ) {
            val plan = rt.projectSession(state)
            val decisionState = state.copy(streamTargetIds = plan.remoteSubscriptionIds)
            rt.streamingReconciler.reconcile(
                decisionState,
                plan.displayedTrackerId,
                plan.displayedTrackerName,
                streamRuntime,
            )
        }
}
