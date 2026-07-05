package com.geovault.tracker.map

import com.geovault.common.coroutines.launchSupervisedCollector
import com.geovault.common.logging.CaptureLogThrottle
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.location.TrackingLifecycleState
import com.geovault.tracker.policy.TrackPointBus
import com.geovault.tracker.presentation.*
import com.geovault.tracker.services.*
import com.geovault.tracker.location.NetworkStatusMonitor
import com.geovault.tracker.location.TrackingPermissionGate
import com.geovault.tracker.streaming.LiveStreamSubscriptionState
import com.geovault.tracker.streaming.StreamingConfig
import com.geovault.tracker.streaming.StreamingDiagnostics
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * Orchestrates the map's streaming lifecycle by wiring together the always-on collectors that
 * drive it. The actual decision logic each collector triggers lives in dedicated collaborators
 * rather than here:
 *  - [StreamRosterResolver]: which trackers should be streamed/displayed right now.
 *  - [StreamTargetReconciler]: turning that resolution into an actual WebSocket lease.
 *  - [TrackPointReducer]: applying one incoming track point to UI state.
 *
 * This class itself keeps only the collector wiring plus the runtime-lifecycle-sync block, which
 * is genuinely cross-cutting (history session boundaries, auto-lock policy, resume evaluation)
 * rather than belonging to any single one of the three collaborators above.
 *
 * Every always-on collector below is started via [launchSupervisedCollector] rather than a bare
 * `viewModelScope.launch { flow.collect { ... } }`: an unhandled exception anywhere in one of
 * these handlers would otherwise permanently kill just that one coroutine with no user-visible
 * symptom beyond "this part of the map silently stopped updating." Since these collectors are
 * exactly the streaming pipeline's core (roster sync, point ingestion, reconcile), a best-effort
 * auto-restart plus a capture-log breadcrumb is far safer than a wedged pipeline.
 */
internal class MapStreamingSubsystem(private val rt: TrackerMapRuntime) {
    companion object {
        private val ROLLING_WINDOW_RECOMPUTE_INTERVAL_MS = TimeUnit.SECONDS.toMillis(60)
    }

    fun startCollectors() {
        // PRELOAD-AT-INIT: read the persisted selected tracker id straight from prefs and
        // seed `rt.uiStateMutable.trail` from the local Room queue ASAP. This races (intentionally)
        // with the first `rt.uiStateMutable.collect` below so the very first render package the map
        // sees already has a trail to fit the camera to. Without this, the launch sequence
        // is "empty render -> 0,0 flash -> ExplicitTrackerLoad -> server fetch -> snap"
        // because `getTrackers()` is metadata-only (no geometry) so the in-memory cache
        // preload inside `reloadTrailFromDatabase` returns null on every cold launch.
        // The Room queue is the only source of truth that survives process death without
        // a network round-trip.
        rt.ports.viewModelScope.launch {
            rt.reload.seedInitialTrailFromLocalQueue()
        }
        // COLD-START ROSTER VALIDATION: `first { it.isNotEmpty() }` rather than reacting to
        // the `TrackersRefreshed` *event* — that SharedFlow has no replay, so if some other
        // screen already fetched+published the roster before this ViewModel/collector
        // existed, the event would already be gone and this would never run. The `trackers`
        // StateFlow always has a current value for a new subscriber, so this sees the roster
        // whether it was fetched before or after this point, without ever validating against
        // the transient pre-fetch `emptyList()` default (which would incorrectly treat every
        // persisted id as invalid on every cold launch).
        rt.ports.viewModelScope.launch {
            val rosterIds = rt.trackerManagementStateStore.trackers
                .first { it.isNotEmpty() }
                .mapNotNullTo(mutableSetOf()) { it.id.trim().takeIf { id -> id.isNotEmpty() } }
            // Only seed if the live `TrackersRefreshed` collector hasn't already advanced
            // this past its initial empty default — avoids reverting it backward to an
            // older roster snapshot if that collector's event happened to land first.
            if (rt.lastKnownRosterTrackerIds.isEmpty()) {
                rt.lastKnownRosterTrackerIds = rosterIds
            }
            rt.streamRosterResolver.validateColdStartAgainstRoster(rosterIds)
        }
        rt.ports.viewModelScope.launchSupervisedCollector(
            tag = "render-resync",
            flow = combine(rt.uiStateMutable, rt.historyRepository.snapshots) { state, _ -> state },
            retryDelayMs = StreamingConfig.collectorRestartDelayMs,
            onError = StreamingDiagnostics::logCollectorRestart,
        ) {
            rt.display.publishRenderPackage()
        }
        rt.ports.viewModelScope.launchSupervisedCollector(
            tag = "group-mode-fit-toggle",
            flow = rt.trackerSettingsRepository.observeSettings()
                .map { it.groupModeFitOnlyActiveTrackers }
                .distinctUntilChanged()
                .drop(1),
            retryDelayMs = StreamingConfig.collectorRestartDelayMs,
            onError = StreamingDiagnostics::logCollectorRestart,
        ) {
            rt.display.publishRenderPackage()
        }
        rt.ports.viewModelScope.launchSupervisedCollector(
            tag = "runtime-state",
            flow = TrackingRuntimeStateStore.state,
            retryDelayMs = StreamingConfig.collectorRestartDelayMs,
            onError = StreamingDiagnostics::logCollectorRestart,
        ) { snap ->
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
            // NO-lastTs: `lastTrackedTimestampMs` used to be part of this signature,
            // which meant it changed on every single GPS fix while recording —
            // requesting a full server-geometry reload per fix even though live
            // points are already applied incrementally via `pointEventChannel`/
            // `TrackPointReducer`. A reload here is only warranted when the
            // *shape* of what should be displayed changes (running/local/selected/
            // session), not when a new point simply arrives.
            val runtimeReloadSignature = buildString {
                append("running=${snap.isRunning}")
                append("|local=${snap.localRecordingActive}")
                append("|selected=${snap.selectedTrackerId.trim()}")
                append("|sessionStart=${snap.sessionStartTimeMs}")
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
            rt.streamRosterResolver.refreshStreamTargets()
            if (runtimeResyncDecision.restartDisplayedStreaming) {
                rt.streamTargetReconciler.bumpReconcileToken()
            }
            if (rt.pendingInitialTrackerForMap && rt.mapReady && rt.mapSurfaceVisible) {
                rt.context.evaluateResumeAfterBackground(allowZeroGap = true)
            }
        }
        rt.ports.viewModelScope.launchSupervisedCollector(
            tag = "point-consumer-forward",
            flow = TrackPointBus.events,
            retryDelayMs = StreamingConfig.collectorRestartDelayMs,
            onError = StreamingDiagnostics::logCollectorRestart,
        ) { point ->
            rt.pointEventChannel.send(point)
        }
        // COMBINED-RECONCILE: this collector handles _state-mutation_ side effects of streaming
        // runtime updates only (mirroring active ids, trimming remote heads, recomputing the
        // status pill, and emitting post-stop lease cleanup). The reconcile call itself moves to
        // the combined flow below so reconcile inputs come from a single coherent snapshot.
        // Deliberately `collect`, not `collectLatest`: the post-stop cleanup branch calls
        // `restoreSelectedTrackerMapContext()`, which resets displayed/mode state and kicks
        // off a trail reload. `collectLatest` would cancel that branch mid-flight the instant
        // a newer snapshot lands (e.g. a fast Stop-then-Start), which could leave the restore
        // half-applied and silently drop the fresher snapshot's own handling since the block
        // never ran to completion. Every field this handler reads is drained from a single
        // snapshot in order instead.
        rt.ports.viewModelScope.launchSupervisedCollector(
            tag = "stream-state",
            flow = rt.liveStreamSubscriptionRepository.state,
            retryDelayMs = StreamingConfig.collectorRestartDelayMs,
            onError = StreamingDiagnostics::logCollectorRestart,
        ) { snapshot ->
            val streamSignature =
                "wants=${snapshot.wantsSubscription}|ended=${snapshot.subscriptionEnded}|connection=${snapshot.connection}|" +
                    "active=${snapshot.activeTargets.sorted()}|failure=${snapshot.failureReason}"
            if (CaptureLogThrottle.shouldLogOnChange("vm_stream_snapshot", streamSignature)) {
                GeoVaultCaptureLog.d(
                    TrackerMapViewModel.TAG,
                    "map_update vm_stream_snapshot wants=${snapshot.wantsSubscription} ended=${snapshot.subscriptionEnded} " +
                        "connection=${snapshot.connection} active=${snapshot.activeTargets.sorted()} failure=${snapshot.failureReason}"
                )
            }
            // STREAM-STATE-MACHINE: an active session = some owner holds a lease AND the
            // service hasn't terminated (cleanly stopped or permanently failed). The
            // active -> ended transition is what triggers lease cleanup below.
            val sessionActive = snapshot.wantsSubscription && !snapshot.subscriptionEnded
            val wasActive = rt.lastObservedStreamingSessionActive
            val hadMapStreamingLease = rt.streamingReconciler.hasMapStreamingLease()
            rt.lastObservedStreamingSessionActive = sessionActive
            rt.uiStateMutable.update { current ->
                val plan = rt.projectSession(
                    state = current.copy(activeStreamedTrackerIds = snapshot.activeTargets),
                    groupSelection = rt.resolveGroupModeSelection(current),
                    visibleRosterTrackerIds = rt.visibleMapRosterTrackerIds(),
                )
                current.copy(
                    activeStreamedTrackerIds = snapshot.activeTargets,
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
                rt.streamTargetReconciler.bumpReconcileToken()
            }
            rt.lastObservedStreamingFailureReason = failureReason
        }
        // FINGERPRINT-DRIVEN REFRESH: pair (previous, current) fingerprints so we can decide
        // whether the change was cosmetic (name/color only — render-only republish) or
        // structural (roster, group membership, per-tracker hidden, map visibility — server
        // refetch). `recent_data_window` is deliberately excluded from both axes and handled
        // by [rt.filterChangeReactor] instead, so a filter edit goes through exactly one path
        // and the cosmetic/structural axes don't double-fire on the same upsert.
        rt.filterChangeReactor.seed(rt.trackerManagementStateStore.trackers.value)
        rt.ports.viewModelScope.launchSupervisedCollector(
            tag = "roster-fingerprint",
            flow = combine(
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
                .drop(1),
            retryDelayMs = StreamingConfig.collectorRestartDelayMs,
            onError = StreamingDiagnostics::logCollectorRestart,
            // `collect`, not `collectLatest`: two structural roster changes landing back
            // to back (e.g. a group edit immediately followed by a tracker delete) must
            // both request their own reload -- `collectLatest` would cancel the first
            // handler before its `requestRuntimeTrailReload`/`refreshStreamTargets` calls
            // ran, dropping that intermediate roster-changed reload request entirely
            // rather than merging it the way `requestRuntimeTrailReload` is designed to.
        ) { (previous, current) ->
            if (current != null) {
                rt.uiStateMutable.value = rt.uiStateMutable.value.copy(renderMetadataSignature = current.combined)
                val structuralChanged = previous == null || previous.structural != current.structural
                val reason = if (structuralChanged) {
                    TrackerMapTrailReloadReason.RosterChanged
                } else {
                    TrackerMapTrailReloadReason.MetadataMapRefresh
                }
                rt.reload.requestRuntimeTrailReload(reason)
                rt.streamRosterResolver.refreshStreamTargets()
            }
        }
        // Events are discrete, per-tracker side effects (invalidate cache + render +
        // request reload). Using `collect` instead of `collectLatest` here is deliberate:
        // collectLatest cancels the previous handler when a new event lands, which can
        // abort a suspending `rt.sessionRequestDeduper.invalidate(...)` mid-flight. The
        // reactor's `observe` already mutated its baseline synchronously by that point,
        // so a cancelled invalidate would silently leak stale cache entries with no
        // second chance to purge them. Ordered draining keeps the contract simple.
        rt.ports.viewModelScope.launchSupervisedCollector(
            tag = "tracker-management-events",
            flow = rt.trackerManagementStateStore.events,
            retryDelayMs = StreamingConfig.collectorRestartDelayMs,
            onError = StreamingDiagnostics::logCollectorRestart,
        ) { event ->
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
                    rt.streamRosterResolver.handleFilterChange(rt.filterChangeReactor.observe(event.tracker))
                }
                is com.geovault.tracker.data.TrackerManagementEvent.TrackerDeleted -> {
                    rt.lastKnownRosterTrackerIds = rt.lastKnownRosterTrackerIds - event.trackerId
                    rt.streamRosterResolver.handleTrackerRemovedFromRoster(event.trackerId)
                    rt.streamRosterResolver.refreshStreamTargets()
                }
                is com.geovault.tracker.data.TrackerManagementEvent.TrackersRefreshed -> {
                    val changes = rt.filterChangeReactor.observeAll(event.trackers)
                    for (change in changes) {
                        rt.streamRosterResolver.handleFilterChange(change)
                    }
                    val nextRosterIds = event.trackers.mapTo(mutableSetOf()) { it.id }
                    val removedIds = rt.lastKnownRosterTrackerIds - nextRosterIds
                    rt.lastKnownRosterTrackerIds = nextRosterIds
                    for (removedId in removedIds) {
                        rt.streamRosterResolver.handleTrackerRemovedFromRoster(removedId)
                    }
                    if (changes.isNotEmpty() || removedIds.isNotEmpty()) rt.streamRosterResolver.refreshStreamTargets()
                }
                else -> Unit
            }
        }
        rt.ports.viewModelScope.launchSupervisedCollector(
            tag = "point-consumer-reduce",
            flow = rt.pointEventChannel.receiveAsFlow(),
            retryDelayMs = StreamingConfig.collectorRestartDelayMs,
            onError = StreamingDiagnostics::logCollectorRestart,
        ) { point ->
            rt.trailReloadMutex.withLock {
                rt.trackPointReducer.reduce(point)
            }
        }
        // IDLE-ROLLING-WINDOW TICKER: see `TrackerMapRuntime.recomputeStaleRollingWindows`.
        // Runs for the lifetime of the map regardless of whether points are actively
        // arriving, since the staleness this guards against only manifests during idle
        // periods (a "last 1h" filter needs to re-exclude points as they age out even
        // with nothing new coming in to trigger a compose on its own). Modeled as a Flow
        // (rather than a bare `while (isActive) { delay(...) }` loop) purely so it can go
        // through the same [launchSupervisedCollector] auto-restart machinery as every other
        // always-on collector here.
        rt.ports.viewModelScope.launchSupervisedCollector(
            tag = "idle-rolling-window-ticker",
            flow = flow {
                while (true) {
                    delay(ROLLING_WINDOW_RECOMPUTE_INTERVAL_MS)
                    emit(Unit)
                }
            },
            retryDelayMs = StreamingConfig.collectorRestartDelayMs,
            onError = StreamingDiagnostics::logCollectorRestart,
        ) {
            if (rt.recomputeStaleRollingWindows()) {
                rt.display.publishRenderPackage()
            }
        }
        // COMBINED-RECONCILE: the single source of truth for reconcile triggering. By combining
        // ui state, streaming runtime, and the explicit invalidation token into one flow we
        // eliminate the dual-collector race where one path could see a fresher uiState than the
        // other saw of streamRuntime (or vice versa). distinctUntilChangedBy on the seed dedupes
        // identical inputs without requiring an internal reconciler-side seed cache.
        rt.ports.viewModelScope.launchSupervisedCollector(
            tag = "reconcile",
            flow = combine(
                rt.uiStateMutable,
                rt.liveStreamSubscriptionRepository.state,
                rt.reconcileTokenMutable,
            ) { ui, stream, token -> ReconcileInputs(ui, stream, token) }
                .distinctUntilChangedBy { rt.streamTargetReconciler.reconcileSeedKey(it.state, it.streamRuntime, it.token) },
            retryDelayMs = StreamingConfig.collectorRestartDelayMs,
            onError = StreamingDiagnostics::logCollectorRestart,
        ) { inputs -> rt.streamTargetReconciler.reconcileStreaming(inputs.state) }
        // HEARTBEAT: fires on a fixed interval independent of point/state cadence, unlike the
        // stream-state collector above which only emits on structural changes and can otherwise
        // go quiet for the entire duration of a session that looks healthy but is actually
        // stalled (the "streamed tracker not updating" failure mode this whole audit started
        // from). This is also where the battery-optimization hint's "unhealthy for N minutes"
        // clock is advanced -- a fixed tick is exactly what that policy needs, so piggy-backing
        // here avoids standing up a second always-on timer just for it.
        rt.ports.viewModelScope.launchSupervisedCollector(
            tag = "heartbeat",
            flow = flow {
                while (true) {
                    delay(StreamingConfig.heartbeatIntervalMs)
                    emit(Unit)
                }
            },
            retryDelayMs = StreamingConfig.collectorRestartDelayMs,
            onError = StreamingDiagnostics::logCollectorRestart,
        ) {
            val streamState = rt.liveStreamSubscriptionRepository.state.value
            val nowMs = System.currentTimeMillis()
            rt.streamingUnhealthySinceMs = when {
                streamState.subscriptionHealthy -> null
                rt.streamingUnhealthySinceMs == null -> nowMs
                else -> rt.streamingUnhealthySinceMs
            }
            if (streamState.wantsSubscription) {
                val lastPointAgeMs = rt.uiStateMutable.value.remoteLastPoints.values
                    .maxOfOrNull { it.timestampMs }
                    ?.let { nowMs - it }
                StreamingDiagnostics.logHeartbeat(
                    wantsSubscription = streamState.wantsSubscription,
                    connection = streamState.connection,
                    activeCount = streamState.activeTargets.size,
                    lastPointAgeMs = lastPointAgeMs,
                    mutexHeld = rt.trailReloadMutex.isLocked,
                )
            }
            val showHint = StreamingBatteryOptimizationHintPolicy.shouldShowHint(
                wantsSubscription = streamState.wantsSubscription,
                connectionHealthy = streamState.subscriptionHealthy,
                unhealthySinceMs = rt.streamingUnhealthySinceMs,
                nowMs = nowMs,
                hasUsableNetwork = NetworkStatusMonitor.hasUsableNetwork(rt.appContext),
                hasBatteryOptimizationExemption = TrackingPermissionGate.hasBatteryOptimizationExemption(rt.appContext),
            )
            if (rt.uiStateMutable.value.batteryOptimizationHintVisible != showHint) {
                rt.uiStateMutable.update { it.copy(batteryOptimizationHintVisible = showHint) }
            }
        }
        rt.streamRosterResolver.refreshStreamTargets()
    }

    internal data class ReconcileInputs(
        val state: TrackerMapUiState,
        val streamRuntime: LiveStreamSubscriptionState,
        val token: Long,
    )
}
