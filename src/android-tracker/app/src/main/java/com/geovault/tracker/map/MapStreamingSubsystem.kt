package com.geovault.tracker.map

import com.geovault.common.coroutines.launchSupervisedCollector
import com.geovault.common.logging.CaptureLogThrottle
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.history.TrackerHistorySessionBoundary
import com.geovault.tracker.location.TrackingLifecycleState
import com.geovault.tracker.policy.TrackPointBus
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.presentation.*
import com.geovault.tracker.services.*
import com.geovault.tracker.location.NetworkStatusMonitor
import com.geovault.tracker.location.TrackingPermissionGate
import com.geovault.tracker.streaming.LiveStreamSubscriptionState
import com.geovault.tracker.streaming.StreamingConfig
import com.geovault.tracker.streaming.StreamingDiagnostics
import kotlinx.coroutines.channels.Channel
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

    // Bookkeeping this subsystem's own collectors read and write to detect
    // transitions (previous-vs-current snapshots, dedupe signatures) or forward events
    // (`pointEventChannel`). Nothing outside this file reads or writes any of these.
    private val pointEventChannel = Channel<TrackPointEvent>(Channel.UNLIMITED)
    // TrackersRefreshed carries the *new* roster only; the store's own `trackers` StateFlow has
    // already been overwritten with that same new list by the time the event reaches us (see
    // `TrackerManagementStateStore.publishTrackers`), so a removal diff needs our own prior
    // snapshot rather than reading through the store. Starts empty so the first refresh after
    // (re)start never reports spurious removals.
    private var lastKnownRosterTrackerIds: Set<String> = emptySet()
    private val filterChangeReactor = TrackerMapFilterChangeReactor()
    private val historySessionBoundary = TrackerHistorySessionBoundary()
    private var lastObservedTrackingRunning: Boolean? = null
    private var lastObservedLocalRecordingActive: Boolean? = null
    private var lastRuntimeTrailReloadSignature: String? = null
    private var lastObservedStreamingSessionActive: Boolean = false
    private var lastObservedStreamingFailureReason: String? = null
    private val runtimeResyncPolicy = TrackerMapRuntimeResyncPolicy()

    /**
     * Wall-clock timestamp of when [com.geovault.tracker.services.LiveStreamSubscriptionState]
     * most recently transitioned from healthy to unhealthy while wanted, or `null` if currently
     * healthy/unwanted. Read and written only from the heartbeat collector below, which runs on a
     * single dispatcher, so no additional synchronization is needed. Feeds
     * [com.geovault.tracker.presentation.StreamingBatteryOptimizationHintPolicy].
     */
    private var streamingUnhealthySinceMs: Long? = null

    /** Closes this subsystem's channel(s). Called once from [TrackerMapRuntime.onCleared]. */
    internal fun close() {
        pointEventChannel.close()
    }

    internal fun startCollectors() {
        wireInitialTrailSeed()
        wireColdStartRosterValidation()
        wireRenderResyncCollector()
        wireGroupModeFitToggleCollector()
        wireRuntimeStateCollector()
        wirePointConsumerForwardCollector()
        wireStreamStateCollector()
        wireRosterFingerprintCollector()
        wireTrackerManagementEventsCollector()
        wirePointConsumerReduceCollector()
        wireIdleRollingWindowTicker()
        wireReconcileCollector()
        wireHeartbeatCollector()
        rt.streamRosterResolver.refreshStreamTargets()
    }

    // PRELOAD-AT-INIT: read the persisted selected tracker id straight from prefs and
    // seed `rt.stateHub.uiStateMutable.trail` from the local Room queue ASAP. This races (intentionally)
    // with the first `rt.stateHub.uiStateMutable.collect` below so the very first render package the map
    // sees already has a trail to fit the camera to. Without this, the launch sequence
    // is "empty render -> 0,0 flash -> ExplicitTrackerLoad -> server fetch -> snap"
    // because `getTrackers()` is metadata-only (no geometry) so the in-memory cache
    // preload inside `reloadTrailFromDatabase` returns null on every cold launch.
    // The Room queue is the only source of truth that survives process death without
    // a network round-trip.
    private fun wireInitialTrailSeed() {
        rt.ports.viewModelScope.launch {
            rt.reload.seedInitialTrailFromLocalQueue()
        }
    }

    // COLD-START ROSTER VALIDATION: `first { it.isNotEmpty() }` rather than reacting to
    // the `TrackersRefreshed` *event* — that SharedFlow has no replay, so if some other
    // screen already fetched+published the roster before this ViewModel/collector
    // existed, the event would already be gone and this would never run. The `trackers`
    // StateFlow always has a current value for a new subscriber, so this sees the roster
    // whether it was fetched before or after this point, without ever validating against
    // the transient pre-fetch `emptyList()` default (which would incorrectly treat every
    // persisted id as invalid on every cold launch).
    private fun wireColdStartRosterValidation() {
        rt.ports.viewModelScope.launch {
            val rosterIds = rt.dependencies.trackerManagementStateStore.trackers
                .first { it.isNotEmpty() }
                .mapNotNullTo(mutableSetOf()) { it.id.trim().takeIf { id -> id.isNotEmpty() } }
            // Only seed if the live `TrackersRefreshed` collector hasn't already advanced
            // this past its initial empty default — avoids reverting it backward to an
            // older roster snapshot if that collector's event happened to land first.
            if (lastKnownRosterTrackerIds.isEmpty()) {
                lastKnownRosterTrackerIds = rosterIds
            }
            rt.streamRosterResolver.validateColdStartAgainstRoster(rosterIds)
        }
    }

    private fun wireRenderResyncCollector() {
        rt.ports.viewModelScope.launchSupervisedCollector(
            tag = "render-resync",
            flow = combine(rt.stateHub.uiStateMutable, rt.dependencies.historyRepository.snapshots) { state, _ -> state },
            retryDelayMs = StreamingConfig.collectorRestartDelayMs,
            onError = StreamingDiagnostics::logCollectorRestart,
        ) {
            rt.display.publishRenderPackage()
        }
    }

    private fun wireGroupModeFitToggleCollector() {
        rt.ports.viewModelScope.launchSupervisedCollector(
            tag = "group-mode-fit-toggle",
            flow = rt.dependencies.trackerSettingsRepository.observeSettings()
                .map { it.groupModeFitOnlyActiveTrackers }
                .distinctUntilChanged()
                .drop(1),
            retryDelayMs = StreamingConfig.collectorRestartDelayMs,
            onError = StreamingDiagnostics::logCollectorRestart,
        ) {
            rt.display.publishRenderPackage()
        }
    }

    private fun wireRuntimeStateCollector() {
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
            // ATOMIC RUNTIME-SYNC: `displayedTrackerId`/`displayedTrackerName` are derived from
            // `current` and written back inside the same `updateAndGet {}` step (rather than a
            // separate read-then-blind-`.value =`-write pair) so a concurrent trail/point commit
            // landing in between can never be silently clobbered by this write reapplying a
            // stale base snapshot.
            val next = rt.stateHub.uiStateMutable.updateAndGet { current ->
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
                current.copy(
                    runtime = effectiveRuntime,
                    displayedTrackerId = displayedTrackerId,
                    displayedTrackerName = displayedTrackerName
                )
            }
            val runtimeSnapshotSignature =
                "mode=${next.mode}|selected=${snap.selectedTrackerId.trim()}|local=${snap.localRecordingActive}|" +
                    "trail=${next.trail.size}|multi=${next.allQueueTrailsByTracker.mapSizes()}|" +
                    "lastTs=${snap.lastTrackedTimestampMs}"
            if (CaptureLogThrottle.shouldLogOnChange("vm_runtime_snapshot", runtimeSnapshotSignature)) {
                GeoVaultCaptureLog.d(
                    TrackerMapViewModel.TAG,
                    "map_update vm_runtime_snapshot mode=${next.mode} selected=${snap.selectedTrackerId.trim()} " +
                        "localActive=${snap.localRecordingActive} localId=${snap.locallyRecordedTrackerId.trim()} " +
                        "sessionStart=${snap.sessionStartTimeMs} lastTs=${snap.lastTrackedTimestampMs} " +
                        "lat=${snap.lastTrackedLatitude} lon=${snap.lastTrackedLongitude} " +
                        "displayed=${next.displayedTrackerId} trail=${next.trail.size} multi=${next.allQueueTrailsByTracker.mapSizes()}"
                )
            }
            val prevLocalRecording = lastObservedLocalRecordingActive
            lastObservedLocalRecordingActive = snap.localRecordingActive
            if (prevLocalRecording != null && !prevLocalRecording && snap.localRecordingActive) {
                val afterRuntime = rt.stateHub.uiStateMutable.value
                when (
                    val autoLock = TrackerMapAutoLockPolicy.resolveAutoLockOnRecordingStart(
                        mode = afterRuntime.mode,
                        displayedTrackerId = afterRuntime.displayedTrackerId,
                        selectedTrackerId = afterRuntime.runtime.selectedTrackerId,
                    )
                ) {
                    is TrackerMapAutoLockOnRecordingResult.SelectionLock -> {
                        rt.stateHub.uiStateMutable.update {
                            it.withAllMapLocksDisabled().copy(selectionLockTrackerId = autoLock.trackerId)
                        }
                    }
                    TrackerMapAutoLockOnRecordingResult.LiveActiveFit -> {
                        rt.stateHub.uiStateMutable.update {
                            it.withAllMapLocksDisabled().copy(liveActiveFitEnabled = true)
                        }
                        rt.display.publishRenderPackage()
                        rt.context.requestFitTrail()
                    }
                    TrackerMapAutoLockOnRecordingResult.None -> Unit
                }
            }
            val runtimeResyncDecision = runtimeResyncPolicy.decide(
                previousIsRunning = lastObservedTrackingRunning,
                currentIsRunning = snap.isRunning,
                mapReady = rt.context.isMapReady,
                mapViewContext = if (next.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
                    TrackerMapViewContext.GROUP
                } else {
                    TrackerMapViewContext.SINGLE_TRACKER
                }
            )
            lastObservedTrackingRunning = snap.isRunning
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
                runtimeReloadSignature != lastRuntimeTrailReloadSignature
            if (shouldRequestReload) {
                lastRuntimeTrailReloadSignature = runtimeReloadSignature
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
                val trackers = rt.dependencies.trackerManagementStateStore.trackers.value
                val trackerId = when {
                    snap.localRecordingActive -> snap.locallyRecordedTrackerId.trim()
                    else -> snap.locallyRecordedTrackerId.trim().ifBlank { snap.selectedTrackerId.trim() }
                }
                if (trackerId.isNotEmpty()) {
                    if (snap.localRecordingActive) {
                        historySessionBoundary.onRecordingStarted(
                            trackerId = trackerId,
                            trackers = trackers,
                            sessionStartMs = rt.activeSessionStartMsForRuntime(snap),
                            repository = rt.dependencies.historyRepository,
                        )
                    } else {
                        historySessionBoundary.onRecordingStopped(
                            trackerId = trackerId,
                            trackers = trackers,
                            dispatcher = rt.dependencies.historyIntentDispatcher,
                        )
                    }
                }
            }
            historySessionBoundary.onRuntimeUpdated(
                runtime = snap,
                trackers = rt.dependencies.trackerManagementStateStore.trackers.value,
                repository = rt.dependencies.historyRepository,
            )
            if (shouldRequestReload) {
                rt.reload.requestRuntimeTrailReload(reloadReason)
            }
            rt.streamRosterResolver.refreshStreamTargets()
            if (runtimeResyncDecision.restartDisplayedStreaming) {
                rt.streamTargetReconciler.bumpReconcileToken()
            }
            if (rt.context.hasPendingInitialTrackerForMap && rt.context.isMapReady && rt.context.isMapSurfaceVisible) {
                rt.context.evaluateResumeAfterBackground(allowZeroGap = true)
            }
        }
    }

    private fun wirePointConsumerForwardCollector() {
        rt.ports.viewModelScope.launchSupervisedCollector(
            tag = "point-consumer-forward",
            flow = TrackPointBus.events,
            retryDelayMs = StreamingConfig.collectorRestartDelayMs,
            onError = StreamingDiagnostics::logCollectorRestart,
        ) { point ->
            pointEventChannel.send(point)
        }
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
    private fun wireStreamStateCollector() {
        rt.ports.viewModelScope.launchSupervisedCollector(
            tag = "stream-state",
            flow = rt.dependencies.liveStreamSubscriptionRepository.state,
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
            val wasActive = lastObservedStreamingSessionActive
            val hadMapStreamingLease = rt.dependencies.streamingReconciler.hasMapStreamingLease()
            lastObservedStreamingSessionActive = sessionActive
            rt.stateHub.uiStateMutable.update { current ->
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
                rt.dependencies.streamingReconciler.consumeStoppedMapStreamingLease()
            ) {
                rt.context.restoreSelectedTrackerMapContext()
            }
            // STREAM-FAILURE-INVALIDATE: a fresh failure reason should re-trigger reconcile so
            // any cleared dedupe in the coordinator can dispatch the next Start cleanly.
            val failureReason = snapshot.failureReason
            val previousFailure = lastObservedStreamingFailureReason
            if (failureReason != null && failureReason != previousFailure) {
                rt.streamTargetReconciler.bumpReconcileToken()
            }
            lastObservedStreamingFailureReason = failureReason
        }
    }

    // FINGERPRINT-DRIVEN REFRESH: pair (previous, current) fingerprints so we can decide
    // whether the change was cosmetic (name/color only — render-only republish) or
    // structural (roster, group membership, per-tracker hidden, map visibility — server
    // refetch). `recent_data_window` is deliberately excluded from both axes and handled
    // by [filterChangeReactor] instead, so a filter edit goes through exactly one path
    // and the cosmetic/structural axes don't double-fire on the same upsert.
    private fun wireRosterFingerprintCollector() {
        filterChangeReactor.seed(rt.dependencies.trackerManagementStateStore.trackers.value)
        rt.ports.viewModelScope.launchSupervisedCollector(
            tag = "roster-fingerprint",
            flow = combine(
                rt.dependencies.trackerManagementStateStore.trackers,
                rt.dependencies.trackerManagementStateStore.groups,
                rt.dependencies.trackerManagementStateStore.mapVisibility,
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
                rt.stateHub.uiStateMutable.update { it.copy(renderMetadataSignature = current.combined) }
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
    }

    // Events are discrete, per-tracker side effects (invalidate cache + render +
    // request reload). Using `collect` instead of `collectLatest` here is deliberate:
    // collectLatest cancels the previous handler when a new event lands, which can
    // abort a suspending `rt.sessionRequestDeduper.invalidate(...)` mid-flight. The
    // reactor's `observe` already mutated its baseline synchronously by that point,
    // so a cancelled invalidate would silently leak stale cache entries with no
    // second chance to purge them. Ordered draining keeps the contract simple.
    private fun wireTrackerManagementEventsCollector() {
        rt.ports.viewModelScope.launchSupervisedCollector(
            tag = "tracker-management-events",
            flow = rt.dependencies.trackerManagementStateStore.events,
            retryDelayMs = StreamingConfig.collectorRestartDelayMs,
            onError = StreamingDiagnostics::logCollectorRestart,
        ) { event ->
            when (event) {
                is com.geovault.tracker.data.TrackerManagementEvent.HistoryCleared -> {
                    val state = rt.stateHub.uiStateMutable.value
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
                                trackers = rt.dependencies.trackerManagementStateStore.trackers.value,
                                dispatcher = rt.dependencies.historyIntentDispatcher,
                                activeSessionStartMs = rt.currentActiveSessionStartMs(),
                            )
                            // Serialize with `trailCommitLock`, matching the reproject/reload
                            // commit convention elsewhere: this reads-then-writes trail state
                            // across two intermediate steps (clear, then reproject), so it must
                            // not interleave with a concurrent reload/live-point commit that also
                            // mutates `trail`/`allQueueTrailsByTracker`.
                            rt.trailCommitLock.withCommitLock {
                                rt.stateHub.uiStateMutable.update { latest ->
                                    val cleared = rt.context.stateWithClearedRenderedTrails(latest, clearedTrackerId)
                                    rt.display.applyHistoryTrailsToState(cleared, rt.projectSession(cleared))
                                }
                            }
                            rt.reload.invalidateLoadedSeed()
                            rt.reload.requestRuntimeTrailReload(TrackerMapTrailReloadReason.HistoryCleared)
                        }
                        TrackerMapViewModel.HistoryClearRefreshAction.NO_OP -> Unit
                    }
                }
                is com.geovault.tracker.data.TrackerManagementEvent.TrackerUpserted -> {
                    rt.streamRosterResolver.handleFilterChange(filterChangeReactor.observe(event.tracker))
                }
                is com.geovault.tracker.data.TrackerManagementEvent.TrackerDeleted -> {
                    lastKnownRosterTrackerIds = lastKnownRosterTrackerIds - event.trackerId
                    // `handleTrackerRemovedFromRoster` triggers its own `refreshStreamTargets()`
                    // when the removed tracker actually warrants one -- no need to call it again
                    // here.
                    rt.streamRosterResolver.handleTrackerRemovedFromRoster(event.trackerId)
                }
                is com.geovault.tracker.data.TrackerManagementEvent.TrackersRefreshed -> {
                    val changes = filterChangeReactor.observeAll(event.trackers)
                    for (change in changes) {
                        rt.streamRosterResolver.handleFilterChange(change)
                    }
                    val nextRosterIds = event.trackers.mapTo(mutableSetOf()) { it.id }
                    val removedIds = lastKnownRosterTrackerIds - nextRosterIds
                    lastKnownRosterTrackerIds = nextRosterIds
                    for (removedId in removedIds) {
                        rt.streamRosterResolver.handleTrackerRemovedFromRoster(removedId)
                    }
                    if (changes.isNotEmpty()) rt.streamRosterResolver.refreshStreamTargets()
                }
                else -> Unit
            }
        }
    }

    private fun wirePointConsumerReduceCollector() {
        rt.ports.viewModelScope.launchSupervisedCollector(
            tag = "point-consumer-reduce",
            flow = pointEventChannel.receiveAsFlow(),
            retryDelayMs = StreamingConfig.collectorRestartDelayMs,
            onError = StreamingDiagnostics::logCollectorRestart,
        ) { point ->
            rt.trailCommitLock.withCommitLock {
                rt.trackPointReducer.reduce(point)
            }
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
    private fun wireIdleRollingWindowTicker() {
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
    }

    // COMBINED-RECONCILE: the single source of truth for reconcile triggering. By combining
    // ui state, streaming runtime, and the explicit invalidation token into one flow we
    // eliminate the dual-collector race where one path could see a fresher uiState than the
    // other saw of streamRuntime (or vice versa). distinctUntilChangedBy on the seed dedupes
    // identical inputs without requiring an internal reconciler-side seed cache.
    private fun wireReconcileCollector() {
        rt.ports.viewModelScope.launchSupervisedCollector(
            tag = "reconcile",
            flow = combine(
                rt.stateHub.uiStateMutable,
                rt.dependencies.liveStreamSubscriptionRepository.state,
                rt.streamTargetReconciler.reconcileToken,
            ) { ui, stream, token -> ReconcileInputs(ui, stream, token) }
                .distinctUntilChangedBy { rt.streamTargetReconciler.reconcileSeedKey(it.state, it.streamRuntime, it.token) },
            retryDelayMs = StreamingConfig.collectorRestartDelayMs,
            onError = StreamingDiagnostics::logCollectorRestart,
        ) { inputs -> rt.streamTargetReconciler.reconcileStreaming(inputs.state) }
    }

    // HEARTBEAT: fires on a fixed interval independent of point/state cadence, unlike the
    // stream-state collector above which only emits on structural changes and can otherwise
    // go quiet for the entire duration of a session that looks healthy but is actually
    // stalled (the "streamed tracker not updating" failure mode this whole audit started
    // from). This is also where the battery-optimization hint's "unhealthy for N minutes"
    // clock is advanced -- a fixed tick is exactly what that policy needs, so piggy-backing
    // here avoids standing up a second always-on timer just for it.
    private fun wireHeartbeatCollector() {
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
            val streamState = rt.dependencies.liveStreamSubscriptionRepository.state.value
            val nowMs = System.currentTimeMillis()
            streamingUnhealthySinceMs = when {
                streamState.subscriptionHealthy -> null
                streamingUnhealthySinceMs == null -> nowMs
                else -> streamingUnhealthySinceMs
            }
            if (streamState.wantsSubscription) {
                val lastPointAgeMs = rt.stateHub.uiStateMutable.value.remoteLastPoints.values
                    .maxOfOrNull { it.timestampMs }
                    ?.let { nowMs - it }
                StreamingDiagnostics.logHeartbeat(
                    wantsSubscription = streamState.wantsSubscription,
                    connection = streamState.connection,
                    activeCount = streamState.activeTargets.size,
                    lastPointAgeMs = lastPointAgeMs,
                    mutexHeld = rt.trailCommitLock.isLocked,
                )
            }
            val showHint = StreamingBatteryOptimizationHintPolicy.shouldShowHint(
                wantsSubscription = streamState.wantsSubscription,
                connectionHealthy = streamState.subscriptionHealthy,
                unhealthySinceMs = streamingUnhealthySinceMs,
                nowMs = nowMs,
                hasUsableNetwork = NetworkStatusMonitor.hasUsableNetwork(rt.dependencies.appContext),
                hasBatteryOptimizationExemption = TrackingPermissionGate.hasBatteryOptimizationExemption(rt.dependencies.appContext),
            )
            if (rt.stateHub.uiStateMutable.value.batteryOptimizationHintVisible != showHint) {
                rt.stateHub.uiStateMutable.update { it.copy(batteryOptimizationHintVisible = showHint) }
            }
        }
    }

    internal data class ReconcileInputs(
        val state: TrackerMapUiState,
        val streamRuntime: LiveStreamSubscriptionState,
        val token: Long,
    )
}
