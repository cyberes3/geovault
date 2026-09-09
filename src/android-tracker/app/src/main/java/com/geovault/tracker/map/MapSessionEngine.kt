package com.geovault.tracker.map

import android.os.SystemClock
import com.geovault.common.concurrent.GeoVaultStateStore
import com.geovault.common.concurrent.TimeWindowedSingleFlight
import com.geovault.common.coroutines.launchSupervisedCollector
import com.geovault.common.logging.CaptureLogThrottle
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.common.net.GeoVaultConnectivity
import com.geovault.tracker.Group
import com.geovault.tracker.data.CatalogSelectionController
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.history.TrackerHistoryKey
import com.geovault.tracker.history.TrackerHistorySessionBoundary
import com.geovault.tracker.history.TrackerHistoryWindowResolver
import com.geovault.tracker.location.TrackingLifecycleState
import com.geovault.tracker.location.TrackingPermissionGate
import com.geovault.tracker.policy.StreamingTargetPolicy
import com.geovault.tracker.policy.StreamingTargetPolicyInput
import com.geovault.tracker.policy.TrackPointBus
import com.geovault.tracker.domain.TrackPoint
import com.geovault.tracker.positioning.RecordingRuntime
import com.geovault.tracker.positioning.TrackingRuntimeSnapshot
import com.geovault.tracker.presentation.MapListNavigationDestination
import com.geovault.tracker.presentation.MapListNavigationTarget
import com.geovault.tracker.presentation.TrackerMapAutoLockOnRecordingResult
import com.geovault.tracker.presentation.TrackerMapDisplayIds
import com.geovault.tracker.presentation.TrackerMapDisplayMode
import com.geovault.tracker.presentation.TrackerMapFilterChangeReactor
import com.geovault.tracker.presentation.TrackerMapFitTrailMode
import com.geovault.tracker.presentation.TrackerMapGroupBoundsResolver
import com.geovault.tracker.presentation.TrackerMapGroupModeOption
import com.geovault.tracker.presentation.TrackerMapRenderMetadataFingerprint
import com.geovault.tracker.presentation.TrackerMapReopenOutcome
import com.geovault.tracker.presentation.TrackerMapResolvedPoint
import com.geovault.tracker.presentation.TrackerMapResumeDecision
import com.geovault.tracker.presentation.TrackerMapResumeInput
import com.geovault.tracker.presentation.TrackerMapSessionIntent
import com.geovault.tracker.presentation.TrackerMapGroupModeSelection
import com.geovault.tracker.presentation.TrackerMapTrailReloadInput
import com.geovault.tracker.presentation.TrackerMapRuntimeInvariant
import com.geovault.tracker.presentation.TrackerMapRuntimeInvariantStatus
import com.geovault.tracker.presentation.TrackerMapRuntimeResyncDecision
import com.geovault.tracker.presentation.TrackerMapRuntimeTransition
import com.geovault.tracker.presentation.TrackerMapSelectionCard
import com.geovault.tracker.presentation.TrackerMapSessionSnapshot
import com.geovault.tracker.presentation.TrackerMapStreamingCommand
import com.geovault.tracker.presentation.TrackerMapStreamingDecisionInput
import com.geovault.tracker.presentation.TrackerMapStreamingPlan
import com.geovault.tracker.presentation.TrackerMapStreamingStatus
import com.geovault.tracker.presentation.TrackerMapStreamingStatusUiModel
import com.geovault.tracker.presentation.TrackerMapTrailReloadReason
import com.geovault.tracker.presentation.TrackerMapUiState
import com.geovault.tracker.presentation.TrackerMapViewContext
import com.geovault.tracker.presentation.TrackerMapViewModel
import com.geovault.tracker.presentation.TrackerMapContextReset
import com.geovault.tracker.presentation.TrackerRosterRemovalOutcome
import com.geovault.tracker.presentation.hasAnyMapLockActive
import com.geovault.tracker.presentation.withAllMapLocksDisabled
import com.geovault.tracker.presentation.withClearedMapSelectionCard
import com.geovault.tracker.runtime.TrackerRuntimeStore
import com.geovault.tracker.streaming.ConnectionPhase
import com.geovault.tracker.streaming.LiveStreamSubscriptionRepository
import com.geovault.tracker.streaming.LiveStreamSubscriptionState
import com.geovault.tracker.streaming.SharedPrefsLiveStreamPersistPort
import com.geovault.tracker.streaming.StreamIntent
import com.geovault.tracker.streaming.StreamingConfig
import com.geovault.tracker.streaming.StreamingDiagnostics
import com.geovault.tracker.streaming.StreamingOwner
import com.geovault.tracker.ui.TrackerPointTimestamps
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

sealed interface MapSessionMailbox {
    data object RuntimeChanged : MapSessionMailbox
    data object StreamChanged : MapSessionMailbox
    data object SurfaceShown : MapSessionMailbox
    data object MapReady : MapSessionMailbox
    data class ModeChanged(val mode: TrackerMapDisplayMode) : MapSessionMailbox
    data class HistoryCleared(val trackerId: String) : MapSessionMailbox
    data class TrackerDeleted(val trackerId: String) : MapSessionMailbox
}

/**
 * Sole writer of [MapSessionDocument] and the map's [StreamingOwner.MAP] lease. Collectors
 * send [MapSessionMailbox] events; one consumer projects the document and writes
 * `setLease(MAP, StreamIntent?)`. Hold skips `setLease`.
 */
internal class MapSessionEngine {
    private val documents = GeoVaultStateStore(MapSessionDocument())
    val document: StateFlow<MapSessionDocument> = documents.state

    private val mailbox = Channel<MapSessionMailbox>(Channel.UNLIMITED)
    private val pointEventChannel = Channel<TrackPoint>(Channel.UNLIMITED)
    private val reconcileTokenMutable = MutableStateFlow(0L)
    private val reconcileToken: StateFlow<Long> = reconcileTokenMutable.asStateFlow()

    private var runtime: TrackerMapRuntime? = null
    private var userStopped: Boolean = false

    private var lastKnownRosterTrackerIds: Set<String> = emptySet()
    private val filterChangeReactor = TrackerMapFilterChangeReactor()
    private val historySessionBoundary = TrackerHistorySessionBoundary()
    private var lastObservedTrackingRunning: Boolean? = null
    private var lastObservedLocalRecordingActive: Boolean? = null
    private var lastRuntimeTrailReloadSignature: String? = null
    private var lastObservedStreamingSessionActive: Boolean = false
    private var lastObservedStreamingFailureReason: String? = null
    private var streamingUnhealthySinceMs: Long? = null

    private var lastBackgroundAtElapsedMs: Long = 0L
    private var mapReady: Boolean = false
    private var pendingResumeEvaluation: Boolean = false
    private var mapSurfaceVisible: Boolean = false
    private var pendingInitialTrackerForMap: Boolean = true
    private var pendingReopenSingleTrackerLoadId: String? = null

    private var cachedPlanSignature: String? = null
    private var cachedPlan: TrackerMapStreamingPlan? = null
    private var sessionRequestFlight: TimeWindowedSingleFlight<String, Any>? = null

    internal val isMapReady: Boolean get() = mapReady
    internal val isMapSurfaceVisible: Boolean get() = mapSurfaceVisible
    internal val hasPendingInitialTrackerForMap: Boolean get() = pendingInitialTrackerForMap

    fun project(
        state: TrackerMapUiState,
        streamIntent: StreamIntent?,
        visibleTrackerIds: Set<String> = emptySet(),
    ) {
        val selectedTrackerId = runtime?.catalogSelectedTrackerId().orEmpty()
        documents.update {
            it.withUiState(state).copy(
                selectedTrackerId = selectedTrackerId,
                visibleTrackerIds = visibleTrackerIds,
                streamIntent = streamIntent,
            )
        }
    }

    fun requestLiveGpsPuck() {
        documents.update {
            it.copy(surface = it.surface.copy(liveGpsPuckRequested = true))
        }
        runtime?.renderEngine?.refreshLocationSurfaceFromSession()
    }

    fun onEvent(event: MapSessionMailbox) {
        send(event)
    }

    internal fun send(event: MapSessionMailbox) {
        mailbox.trySend(event)
    }

    internal fun markUserStopped() {
        userStopped = true
    }

    internal fun start(rt: TrackerMapRuntime) {
        runtime = rt
        sessionRequestFlight = TimeWindowedSingleFlight(
            scope = rt.ports.viewModelScope,
            windowMs = SESSION_REQUEST_DEDUPE_WINDOW_MS,
        )
        startMailboxConsumer(rt)
        startCollectors(rt)
    }

    internal fun invalidateSessionRequests(trackerId: String) {
        val flight = sessionRequestFlight ?: return
        invalidateSessionRequests(flight, trackerId)
    }

    internal fun clearSessionRequests() {
        sessionRequestFlight?.clear()
    }

    internal fun close() {
        mailbox.close()
        pointEventChannel.close()
    }

    internal fun resolvePlan(state: TrackerMapUiState): TrackerMapStreamingPlan {
        val rt = runtime ?: return emptyPlan(state)
        val signature = planSignature(state)
        val plan = cachedPlan
        if (plan != null && signature == cachedPlanSignature) return plan
        val fresh = rt.projectSession(state)
        cachedPlanSignature = signature
        cachedPlan = fresh
        return fresh
    }

    internal fun warmPlan(state: TrackerMapUiState, plan: TrackerMapStreamingPlan) {
        cachedPlanSignature = planSignature(state)
        cachedPlan = plan
    }

    internal fun bumpReconcileToken() {
        reconcileTokenMutable.value = reconcileTokenMutable.value + 1L
    }

    private fun startMailboxConsumer(rt: TrackerMapRuntime) {
        rt.ports.viewModelScope.launchSupervisedCollector(
            tag = "session-mailbox",
            flow = mailbox.receiveAsFlow(),
            retryDelayMs = StreamingConfig.collectorRestartDelayMs,
            onError = StreamingDiagnostics::logCollectorRestart,
        ) { event ->
            consume(rt, event)
        }
    }

    private suspend fun consume(rt: TrackerMapRuntime, event: MapSessionMailbox) {
        when (event) {
            is MapSessionMailbox.HistoryCleared -> {
                applyHistoryCleared(rt, event.trackerId)
                return
            }
            is MapSessionMailbox.TrackerDeleted -> {
                lastKnownRosterTrackerIds = lastKnownRosterTrackerIds - event.trackerId
                handleTrackerRemovedFromRoster(event.trackerId)
                return
            }
            else -> Unit
        }
        if (event is MapSessionMailbox.ModeChanged) {
            userStopped = false
        }
        val state = rt.stateHub.uiStateMutable.value
        val plan = rt.projectSession(state)
        val streamIntent = streamIntentFor(plan, state)
        project(
            state = state,
            streamIntent = streamIntent,
            visibleTrackerIds = plan.visibleRosterTrackerIds,
        )
        if (userStopped && event !is MapSessionMailbox.ModeChanged) {
            return
        }
        applyMapLease(rt, plan, state)
    }

    private suspend fun applyHistoryCleared(rt: TrackerMapRuntime, trackerId: String) {
        val state = rt.stateHub.uiStateMutable.value
        GeoVaultCaptureLog.i(
            TrackerMapViewModel.TAG,
            "map_update vm_history_cleared_event track=${trackerId.trim()} " +
                "mode=${state.mode} displayed=${state.displayedTrackerId.trim()} selected=${rt.catalogSelectedTrackerId().trim()}"
        )
        when (
            resolveHistoryClearRefreshAction(
                mode = state.mode,
                displayedTrackerId = state.displayedTrackerId,
                selectedTrackerId = rt.catalogSelectedTrackerId(),
                clearedTrackerId = trackerId
            )
        ) {
            HistoryClearRefreshAction.REFRESH_GROUP_OR_ALL,
            HistoryClearRefreshAction.REFRESH_DISPLAYED_SINGLE,
            HistoryClearRefreshAction.REFRESH_SELECTED_SINGLE -> {
                val clearedTrackerId = trackerId.trim()
                GeoVaultCaptureLog.i(
                    TrackerMapViewModel.TAG,
                    "map_update vm_history_clear_apply track=$clearedTrackerId action=refresh",
                )
                invalidateSessionRequests(trackerId)
                MapTrailEngine.dispatchHistoryClear(
                    trackerId = clearedTrackerId,
                    trackers = rt.catalog().trackers,
                    dispatcher = rt.dependencies.historyIntentDispatcher,
                    activeSessionStartMs = rt.activeSessionStartMsForTracker(clearedTrackerId),
                )
                rt.withTrailCommit {
                    rt.stateHub.uiStateMutable.update { latest ->
                        val effectiveDisplayedId = rt.displayedTrackerId(latest)
                        rt.trailEngine.removeTrackerGeometry(
                            trackerId = clearedTrackerId,
                            clearSingleTrail = latest.mode == TrackerMapDisplayMode.SINGLE_SESSION &&
                                (effectiveDisplayedId == clearedTrackerId.trim() ||
                                    rt.catalogSelectedTrackerId().trim() == clearedTrackerId.trim()),
                        )
                        rt.trailEngine.applyHistoryTrailsToState(latest, rt.projectSession(latest))
                    }
                }
                rt.trailEngine.invalidateLoadedSeed()
                rt.trailEngine.requestRuntimeTrailReload(TrackerMapTrailReloadReason.HistoryCleared)
            }
            HistoryClearRefreshAction.NO_OP -> Unit
        }
    }

    private fun streamIntentFor(
        plan: TrackerMapStreamingPlan,
        state: TrackerMapUiState,
    ): StreamIntent? {
        return when (val command = resolveStreamingCommand(plan, state)) {
            is TrackerMapStreamingCommand.Start -> StreamIntent(
                trackerIds = command.trackerIds,
                displayName = command.trackerName,
                locallyRecordedTrackerId = runtime?.recording()?.locallyRecordedTrackerId?.trim()?.ifBlank { null },
            )
            TrackerMapStreamingCommand.Stop,
            TrackerMapStreamingCommand.NoOp -> null
        }
    }

    private fun applyMapLease(
        rt: TrackerMapRuntime,
        plan: TrackerMapStreamingPlan,
        state: TrackerMapUiState,
    ) {
        val command = resolveStreamingCommand(plan, state)
        val repository = rt.dependencies.liveStreamSubscriptionRepository
        when (command) {
            is TrackerMapStreamingCommand.Start -> {
                repository.setLease(
                    StreamingOwner.MAP,
                    StreamIntent(
                        trackerIds = command.trackerIds,
                        displayName = command.trackerName,
                        locallyRecordedTrackerId = rt.recording().locallyRecordedTrackerId.trim().ifBlank { null },
                    ),
                )
            }
            TrackerMapStreamingCommand.Stop -> {
                val historyOnlyHold = plan.mode == TrackerMapDisplayMode.SINGLE_SESSION &&
                    plan.displayedTrackerId.trim().isNotEmpty()
                if (historyOnlyHold && repository.state.value.leases[StreamingOwner.MAP] == null) {
                    return
                }
                repository.setLease(StreamingOwner.MAP, null)
            }
            TrackerMapStreamingCommand.NoOp -> Unit
        }
    }

    private fun startCollectors(rt: TrackerMapRuntime) {
        wireInitialTrailSeed(rt)
        wireColdStartRosterValidation(rt)
        wireRuntimeStateCollector(rt)
        wirePointConsumerForwardCollector(rt)
        wireStreamStateCollector(rt)
        wireRosterFingerprintCollector(rt)
        wireTrackerManagementEventsCollector(rt)
        wirePointConsumerReduceCollector(rt)
        wireIdleRollingWindowTicker(rt)
        wireReconcileCollector(rt)
        wireHeartbeatCollector(rt)
        wireSessionDocumentCollector(rt)
        refreshStreamTargets()
    }

    private fun wireSessionDocumentCollector(rt: TrackerMapRuntime) {
        rt.ports.viewModelScope.launchSupervisedCollector(
            tag = "session-document",
            flow = rt.stateHub.uiStateMutable,
            retryDelayMs = StreamingConfig.collectorRestartDelayMs,
            onError = StreamingDiagnostics::logCollectorRestart,
        ) { state ->
            documents.update { current ->
                current.withUiState(state).copy(selectedTrackerId = rt.catalogSelectedTrackerId())
            }
        }
    }

    private fun wireInitialTrailSeed(rt: TrackerMapRuntime) {
        rt.ports.viewModelScope.launch {
            rt.trailEngine.seedInitialTrailFromLocalQueue()
        }
    }

    private fun wireColdStartRosterValidation(rt: TrackerMapRuntime) {
        rt.ports.viewModelScope.launch {
            val rosterIds = rt.dependencies.catalogStateStore.state
                .first { it.trackers.isNotEmpty() }
                .trackers
                .mapNotNullTo(mutableSetOf()) { it.id.trim().takeIf { id -> id.isNotEmpty() } }
            if (lastKnownRosterTrackerIds.isEmpty()) {
                lastKnownRosterTrackerIds = rosterIds
            }
            validateColdStartAgainstRoster(rosterIds)
        }
    }

    private fun wireRuntimeStateCollector(rt: TrackerMapRuntime) {
        rt.ports.viewModelScope.launchSupervisedCollector(
            tag = "runtime-state",
            flow = TrackerRuntimeStore.state,
            retryDelayMs = StreamingConfig.collectorRestartDelayMs,
            onError = StreamingDiagnostics::logCollectorRestart,
        ) { document ->
            val snap = document.recording
            val effectiveLifecycleState = if (!snap.isRunning && snap.startupActive) {
                TrackingLifecycleState.STARTING
            } else {
                snap.lifecycleState
            }
            val effectiveRuntime = snap.copy(
                isRunning = snap.isRunning,
                lifecycleState = effectiveLifecycleState
            )
            val next = rt.stateHub.uiStateMutable.updateAndGet { current ->
                val displayedTrackerId = if (current.displayedTrackerId.isBlank()) {
                    rt.catalogSelectedTrackerId()
                } else {
                    current.displayedTrackerId
                }
                val displayedTrackerName = if (current.displayedTrackerName.isBlank()) {
                    rt.catalogSelectedTrackerName()
                } else {
                    current.displayedTrackerName
                }
                current.copy(
                    displayedTrackerId = displayedTrackerId,
                    displayedTrackerName = displayedTrackerName
                )
            }
            val trails = rt.trailEngine.trail.value
            val runtimeSnapshotSignature =
                "mode=${next.mode}|selected=${snap.selectedTrackerId.trim()}|local=${snap.localRecordingActive}|" +
                    "trail=${trails.singleTrail.size}|multi=${trails.tracksByTrackerId.mapSizes()}|" +
                    "lastTs=${snap.lastTrackedTimestampMs}"
            if (CaptureLogThrottle.shouldLogOnChange("vm_runtime_snapshot", runtimeSnapshotSignature)) {
                GeoVaultCaptureLog.d(
                    TrackerMapViewModel.TAG,
                    "map_update vm_runtime_snapshot mode=${next.mode} selected=${snap.selectedTrackerId.trim()} " +
                        "localActive=${snap.localRecordingActive} localId=${snap.locallyRecordedTrackerId.trim()} " +
                        "sessionStart=${snap.sessionStartTimeMs} lastTs=${snap.lastTrackedTimestampMs} " +
                        "lat=${snap.lastTrackedLatitude} lon=${snap.lastTrackedLongitude} " +
                        "displayed=${next.displayedTrackerId} trail=${trails.singleTrail.size} multi=${trails.tracksByTrackerId.mapSizes()}"
                )
            }
            val prevLocalRecording = lastObservedLocalRecordingActive
            lastObservedLocalRecordingActive = snap.localRecordingActive
            if (prevLocalRecording != null && !prevLocalRecording && snap.localRecordingActive) {
                val afterRuntime = rt.stateHub.uiStateMutable.value
                when (
                    val autoLock = resolveAutoLockOnRecordingStart(
                        mode = afterRuntime.mode,
                        displayedTrackerId = afterRuntime.displayedTrackerId,
                        selectedTrackerId = rt.catalogSelectedTrackerId(),
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
                        rt.ports.viewModelScope.launch { rt.renderEngine.publishRenderPackage() }
                        requestFitTrail()
                    }
                    TrackerMapAutoLockOnRecordingResult.None -> Unit
                }
            }
            val runtimeResyncDecision = decideRuntimeResync(
                previousIsRunning = lastObservedTrackingRunning,
                currentIsRunning = snap.isRunning,
                mapReady = isMapReady,
                isGroup = next.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            )
            lastObservedTrackingRunning = snap.isRunning
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
                val trackers = rt.catalog().trackers
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
                trackers = rt.catalog().trackers,
                repository = rt.dependencies.historyRepository,
            )
            if (shouldRequestReload) {
                rt.trailEngine.requestRuntimeTrailReload(reloadReason)
            }
            refreshStreamTargets()
            if (runtimeResyncDecision.restartDisplayedStreaming) {
                bumpReconcileToken()
            }
            if (hasPendingInitialTrackerForMap && isMapReady && isMapSurfaceVisible) {
                evaluateResumeAfterBackground(allowZeroGap = true)
            }
            send(MapSessionMailbox.RuntimeChanged)
        }
    }

    private fun wirePointConsumerForwardCollector(rt: TrackerMapRuntime) {
        rt.ports.viewModelScope.launchSupervisedCollector(
            tag = "point-consumer-forward",
            flow = TrackPointBus.events,
            retryDelayMs = StreamingConfig.collectorRestartDelayMs,
            onError = StreamingDiagnostics::logCollectorRestart,
        ) { point ->
            pointEventChannel.send(point)
        }
    }

    private fun wireStreamStateCollector(rt: TrackerMapRuntime) {
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
            val sessionActive = snapshot.wantsSubscription && !snapshot.subscriptionEnded
            val wasActive = lastObservedStreamingSessionActive
            val hadMapStreamingLease = hasMapStreamingLease(rt.dependencies.liveStreamSubscriptionRepository)
            lastObservedStreamingSessionActive = sessionActive
            val nextState = rt.stateHub.uiStateMutable.updateAndGet { current ->
                current.copy(
                    activeStreamedTrackerIds = snapshot.activeTargets,
                    streamingStatus = resolveStreamingStatus(
                        snapshot = snapshot,
                        mapLeaseIds = snapshot.leases[StreamingOwner.MAP]?.trackerIds
                            ?: document.value.streamIntent?.trackerIds
                            ?: emptySet(),
                    ),
                )
            }
            val plan = rt.projectSession(
                state = nextState,
                groupSelection = rt.resolveGroupModeSelection(nextState),
                visibleRosterTrackerIds = rt.visibleMapRosterTrackerIds(),
            )
            if (
                snapshot.subscriptionEnded &&
                document.value.visibleTrackerIds.isEmpty() &&
                plan.remoteSubscriptionIds.isEmpty()
            ) {
                rt.trailEngine.clearRemoteLastPoints()
            } else {
                rt.trailEngine.replaceRemoteLastPoints(
                    filterRemoteLastPointsForAcceptedIds(
                        remoteLastPoints = rt.trailEngine.trail.value.remoteLastPoints,
                        acceptedRemoteTrackerIds = plan.acceptedRemoteTrackerIds,
                    )
                )
            }
            if ((wasActive || hadMapStreamingLease) &&
                snapshot.subscriptionEnded &&
                consumeStoppedMapStreamingLease(rt.dependencies.liveStreamSubscriptionRepository)
            ) {
                markUserStopped()
                restoreSelectedTrackerMapContext()
            }
            val failureReason = snapshot.failureReason
            val previousFailure = lastObservedStreamingFailureReason
            if (failureReason != null && failureReason != previousFailure) {
                bumpReconcileToken()
            }
            lastObservedStreamingFailureReason = failureReason
            send(MapSessionMailbox.StreamChanged)
        }
    }

    private fun wireRosterFingerprintCollector(rt: TrackerMapRuntime) {
        filterChangeReactor.seed(rt.catalog().trackers)
        rt.ports.viewModelScope.launchSupervisedCollector(
            tag = "roster-fingerprint",
            flow = rt.dependencies.catalogStateStore.state
                .map { catalog ->
                    TrackerMapRenderMetadataFingerprint.from(
                        catalog.trackers,
                        catalog.groups,
                        catalog.mapVisibility,
                    )
                }
                .distinctUntilChanged()
                .scan<TrackerMapRenderMetadataFingerprint, Pair<TrackerMapRenderMetadataFingerprint?, TrackerMapRenderMetadataFingerprint?>>(
                    null to null
                ) { acc, next -> acc.second to next }
                .drop(1),
            retryDelayMs = StreamingConfig.collectorRestartDelayMs,
            onError = StreamingDiagnostics::logCollectorRestart,
        ) { (previous, current) ->
            if (current != null) {
                rt.stateHub.uiStateMutable.update { it.copy(renderMetadataSignature = current.combined) }
                val structuralChanged = previous == null || previous.structural != current.structural
                val reason = if (structuralChanged) {
                    TrackerMapTrailReloadReason.RosterChanged
                } else {
                    TrackerMapTrailReloadReason.MetadataMapRefresh
                }
                rt.trailEngine.requestRuntimeTrailReload(reason)
                refreshStreamTargets()
                send(MapSessionMailbox.StreamChanged)
            }
        }
    }

    private fun wireTrackerManagementEventsCollector(rt: TrackerMapRuntime) {
        rt.ports.viewModelScope.launchSupervisedCollector(
            tag = "tracker-management-events",
            flow = rt.dependencies.catalogStateStore.events,
            retryDelayMs = StreamingConfig.collectorRestartDelayMs,
            onError = StreamingDiagnostics::logCollectorRestart,
        ) { event ->
            when (event) {
                is com.geovault.tracker.data.TrackerManagementEvent.HistoryCleared -> {
                    send(MapSessionMailbox.HistoryCleared(event.trackerId))
                }
                is com.geovault.tracker.data.TrackerManagementEvent.TrackerUpserted -> {
                    handleFilterChange(filterChangeReactor.observe(event.tracker))
                }
                is com.geovault.tracker.data.TrackerManagementEvent.TrackerDeleted -> {
                    send(MapSessionMailbox.TrackerDeleted(event.trackerId))
                }
                is com.geovault.tracker.data.TrackerManagementEvent.TrackersRefreshed -> {
                    val changes = filterChangeReactor.observeAll(event.trackers)
                    for (change in changes) {
                        handleFilterChange(change)
                    }
                    val nextRosterIds = event.trackers.mapTo(mutableSetOf()) { it.id }
                    val removedIds = lastKnownRosterTrackerIds - nextRosterIds
                    lastKnownRosterTrackerIds = nextRosterIds
                    for (removedId in removedIds) {
                        handleTrackerRemovedFromRoster(removedId)
                    }
                    if (changes.isNotEmpty()) refreshStreamTargets()
                }
                else -> Unit
            }
        }
    }

    private fun wirePointConsumerReduceCollector(rt: TrackerMapRuntime) {
        rt.ports.viewModelScope.launchSupervisedCollector(
            tag = "point-consumer-reduce",
            flow = pointEventChannel.receiveAsFlow(),
            retryDelayMs = StreamingConfig.collectorRestartDelayMs,
            onError = StreamingDiagnostics::logCollectorRestart,
        ) { point ->
            rt.withTrailCommit {
                rt.trailEngine.reduce(point)
            }
        }
    }

    private fun wireIdleRollingWindowTicker(rt: TrackerMapRuntime) {
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
                rt.ports.viewModelScope.launch { rt.renderEngine.publishRenderPackage() }
            }
        }
    }

    private fun wireReconcileCollector(rt: TrackerMapRuntime) {
        rt.ports.viewModelScope.launchSupervisedCollector(
            tag = "reconcile",
            flow = combine(
                rt.stateHub.uiStateMutable,
                rt.dependencies.liveStreamSubscriptionRepository.state,
                reconcileToken,
            ) { ui, stream, token -> ReconcileInputs(ui, stream, token) }
                .distinctUntilChangedBy { reconcileSeedKey(it.state, it.streamRuntime, it.token) },
            retryDelayMs = StreamingConfig.collectorRestartDelayMs,
            onError = StreamingDiagnostics::logCollectorRestart,
        ) {
            send(MapSessionMailbox.StreamChanged)
        }
    }

    private fun wireHeartbeatCollector(rt: TrackerMapRuntime) {
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
                val lastPointAgeMs = rt.trailEngine.trail.value.remoteLastPoints.values
                    .maxOfOrNull { it.timeMs }
                    ?.let { nowMs - it }
                StreamingDiagnostics.logHeartbeat(
                    wantsSubscription = streamState.wantsSubscription,
                    connection = streamState.connection,
                    activeCount = streamState.activeTargets.size,
                    lastPointAgeMs = lastPointAgeMs,
                    mutexHeld = rt.isTrailCommitLocked,
                )
            }
            val showHint = shouldShowBatteryOptimizationHint(
                wantsSubscription = streamState.wantsSubscription,
                connectionHealthy = streamState.subscriptionHealthy,
                unhealthySinceMs = streamingUnhealthySinceMs,
                nowMs = nowMs,
                hasUsableNetwork = GeoVaultConnectivity.hasValidatedInternet(rt.dependencies.appContext),
                hasBatteryOptimizationExemption = TrackingPermissionGate.hasBatteryOptimizationExemption(rt.dependencies.appContext),
            )
            if (rt.stateHub.uiStateMutable.value.batteryOptimizationHintVisible != showHint) {
                rt.stateHub.uiStateMutable.update { it.copy(batteryOptimizationHintVisible = showHint) }
            }
        }
    }

    internal fun refreshStreamTargets() {
        val rt = runtime ?: return
        val state = rt.stateHub.uiStateMutable.value
        val groupSelection = rt.resolveGroupModeSelection(state)
        val visibleRosterTrackerIds = rt.visibleMapRosterTrackerIds()
        val plan = rt.projectSession(
            state = state,
            groupSelection = groupSelection,
            visibleRosterTrackerIds = visibleRosterTrackerIds,
        )
        val previousMapLeaseIds = document.value.streamIntent?.trackerIds
            ?: rt.dependencies.liveStreamSubscriptionRepository.state.value.leases[StreamingOwner.MAP]?.trackerIds
            ?: emptySet()
        applyStreamTargetPlan(state, plan, previousMapLeaseIds)
        rt.trailEngine.requestTrailReloadForStreamingScopeChange(state, plan, groupSelection, previousMapLeaseIds)
    }

    private fun applyStreamTargetPlan(
        state: TrackerMapUiState,
        plan: TrackerMapStreamingPlan,
        previousStreamTargetIds: Set<String>,
    ) {
        val rt = runtime ?: return
        val nextStreamTargetIds = plan.remoteSubscriptionIds
        val autoSelectionLockId = resolveAutoSelectionLockForSingleStream(
            mode = state.mode,
            previousTargets = previousStreamTargetIds,
            nextTargets = nextStreamTargetIds,
            displayedTrackerId = plan.displayedTrackerId,
        )
        rt.trailEngine.replaceRemoteLastPoints(
            filterRemoteLastPointsForAcceptedIds(
                remoteLastPoints = rt.trailEngine.trail.value.remoteLastPoints,
                acceptedRemoteTrackerIds = plan.acceptedRemoteTrackerIds,
            )
        )
        rt.stateHub.uiStateMutable.update { cur ->
            val baseNext = cur.copy(
                currentGroupId = if (cur.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
                    plan.resolvedGroupId
                } else {
                    cur.currentGroupId
                },
                groupModeOptions = if (cur.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
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
        warmPlan(rt.stateHub.uiStateMutable.value, plan)
    }

    internal suspend fun handleFilterChange(change: TrackerMapFilterChangeReactor.FilterChange) {
        val rt = runtime ?: return
        when (change) {
            is TrackerMapFilterChangeReactor.FilterChange.None -> Unit
            is TrackerMapFilterChangeReactor.FilterChange.Refresh -> {
                invalidateSessionRequests(change.trackerId)
                recomposeHistoryForTracker(change.trackerId)
                rt.ports.viewModelScope.launch { rt.renderEngine.publishRenderPackage() }
                rt.trailEngine.requestRuntimeTrailReload(TrackerMapTrailReloadReason.RecentDataWindowChanged)
            }
        }
    }

    internal suspend fun handleTrackerRemovedFromRoster(trackerId: String) {
        val rt = runtime ?: return
        var outcome: TrackerRosterRemovalOutcome? = null
        rt.withTrailCommit {
            rt.stateHub.uiStateMutable.update { latest ->
                val mapLeaseIds = document.value.streamIntent?.trackerIds
                    ?: rt.dependencies.liveStreamSubscriptionRepository.state.value.leases[StreamingOwner.MAP]?.trackerIds
                    ?: emptySet()
                val result = applyRosterRemoval(
                    state = latest,
                    removedTrackerId = trackerId,
                    mapLeaseIds = mapLeaseIds,
                    trails = rt.trailEngine.trail.value,
                )
                outcome = result
                if (result.changed) {
                    rt.trailEngine.replace(result.nextTrails)
                    result.nextState
                } else {
                    latest
                }
            }
        }
        val resolvedOutcome = outcome ?: return
        if (!resolvedOutcome.changed) return
        warmPlan(rt.stateHub.uiStateMutable.value, rt.projectSession(rt.stateHub.uiStateMutable.value))
        GeoVaultCaptureLog.i(
            TrackerMapViewModel.TAG,
            "map_update roster_removal trackerId=$trackerId shouldRefreshStreamTargets=${resolvedOutcome.shouldRefreshStreamTargets}"
        )
        rt.ports.viewModelScope.launch { rt.renderEngine.publishRenderPackage() }
        if (resolvedOutcome.shouldRefreshStreamTargets) {
            refreshStreamTargets()
        }
    }

    internal suspend fun validateColdStartAgainstRoster(rosterIds: Set<String>) {
        val rt = runtime ?: return
        val state = rt.stateHub.uiStateMutable.value
        val displayedId = state.displayedTrackerId.trim()
        if (displayedId.isNotEmpty() && displayedId !in rosterIds) {
            handleTrackerRemovedFromRoster(displayedId)
        }
        val cardTrackerId = rt.stateHub.uiStateMutable.value.selectedMapTracker?.trackerId?.trim().orEmpty()
        if (cardTrackerId.isNotEmpty() && cardTrackerId !in rosterIds) {
            handleTrackerRemovedFromRoster(cardTrackerId)
        }

        val persist = SharedPrefsLiveStreamPersistPort(rt.ports.application)
        val (persistedStreamTargetIds, persistName) = persist.read()
        val invalidStreamTargetIds = persistedStreamTargetIds - rosterIds
        if (invalidStreamTargetIds.isNotEmpty()) {
            rt.dependencies.liveStreamSubscriptionRepository.pruneInvalidTargets(rosterIds)
            val prunedIds = persistedStreamTargetIds.intersect(rosterIds)
            if (prunedIds.isEmpty()) {
                persist.clear()
            } else {
                persist.commit(prunedIds, persistName)
            }
            for (invalidId in invalidStreamTargetIds) {
                handleTrackerRemovedFromRoster(invalidId)
            }
        }

        val runtimeSnapshot = rt.recording()
        if (!runtimeSnapshot.localRecordingActive) {
            val persistedSelectedId = CatalogSelectionController.persistedTrackerId(rt.ports.application)
            if (persistedSelectedId.isNotEmpty() && persistedSelectedId !in rosterIds) {
                GeoVaultCaptureLog.w(
                    TrackerMapViewModel.TAG,
                    "map_update cold_start_prune_selected_tracker id=$persistedSelectedId",
                )
                TrackerAppServices.from(rt.ports.application)
                    .catalogSelectionController()
                    .clearSelectedTracker(rt.ports.application)
            }
        }
    }

    private fun recomposeHistoryForTracker(trackerId: String) {
        val rt = runtime ?: return
        val normalized = trackerId.trim()
        if (normalized.isEmpty()) return
        val sessionStart = rt.activeSessionStartMsForTracker(normalized)
        val keys = rt.dependencies.historyRepository.snapshots.value.keys.filter { it.normalizedTrackerId == normalized }
        if (keys.isEmpty()) {
            val tracker = rt.catalog().trackers.firstOrNull { it.id.trim() == normalized }
            val window = TrackerHistoryWindowResolver.fromTracker(tracker)
            rt.dependencies.historyTrunkIngestor.recompose(
                key = TrackerHistoryKey(normalized, window),
                activeSessionStartMs = sessionStart,
            )
            return
        }
        for (key in keys) {
            rt.dependencies.historyTrunkIngestor.recompose(
                key = key,
                activeSessionStartMs = sessionStart,
            )
        }
    }

    private fun reconcileSeedKey(
        state: TrackerMapUiState,
        streamRuntime: LiveStreamSubscriptionState,
        token: Long,
    ): String {
        val plan = resolvePlan(state)
        val streamIdsSignature = plan.remoteSubscriptionIds.toList().sorted().joinToString(separator = ",")
        val activeIdsSignature = streamRuntime.activeTargets
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .sorted()
            .joinToString(separator = ",")
        val trackingActiveOrStarting = runtime?.recording()?.localRecordingActive == true
        val selectedTrackerId = runtime?.catalogSelectedTrackerId().orEmpty().trim()
        return "${state.mode}|$trackingActiveOrStarting|$streamIdsSignature|${plan.displayedTrackerId}|" +
            "$selectedTrackerId|${plan.displayedTrackerName}|" +
            "${streamRuntime.wantsSubscription}|${streamRuntime.connection.name}|$activeIdsSignature|" +
            "${streamRuntime.failureReason.orEmpty()}|$token"
    }

    internal fun setMode(mode: TrackerMapDisplayMode) {
        val rt = runtime ?: return
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
        val rt = runtime ?: return
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
        val rt = runtime ?: return
        val normalizedId = trackerId.trim()
        if (normalizedId.isEmpty()) return
        val state = rt.stateHub.uiStateMutable.value
        val resolvedName = trackerName?.trim().orEmpty().ifBlank {
            if (normalizedId == rt.catalogSelectedTrackerId()) {
                rt.catalogSelectedTrackerName()
            } else {
                rt.catalog().trackers
                    .firstOrNull { it.id == normalizedId }
                    ?.name
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: state.displayedTrackerName.takeIf { state.displayedTrackerId == normalizedId }
                    ?: normalizedId
            }
        }
        val trails = rt.trailEngine.trail.value
        val isAlive = TrackerMapGroupBoundsResolver.isTrackerActive(
            trackerId = normalizedId,
            trailsByTracker = trails.tracksByTrackerId,
            remoteLastPoints = trails.remoteLastPoints,
            trackers = rt.catalog().trackers,
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
        val rt = runtime ?: return
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
        val rt = runtime ?: return
        val state = rt.stateHub.uiStateMutable.value
        val selectedId = rt.catalogSelectedTrackerId().trim()
        markUserStopped()
        stopForegroundStreaming(rt.dependencies.liveStreamSubscriptionRepository)
        if (selectedId.isBlank()) {
            pendingInitialTrackerForMap = true
            pendingResumeEvaluation = true
            return
        }
        val selectedName = rt.catalogSelectedTrackerName()
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
        val rt = runtime ?: return resolveListNavigation(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            currentGroupId = "",
            preferredTrackerId = "",
            isCurrentGroupOwned = null,
            isPreferredTrackerOwned = null,
        )
        val state = rt.stateHub.uiStateMutable.value
        val preferredTrackerId = preferredTrackerIdOverride?.trim().orEmpty().ifBlank {
            rt.displayedTrackerId(state)
        }.ifBlank {
            rt.catalogSelectedTrackerId().trim()
        }.ifBlank { "" }
        val preferredTrackerOwned = rt.catalog().trackers
            .firstOrNull { it.id == preferredTrackerId }
            ?.isOwner()
        val currentGroupOwned = rt.catalog().groups
            .firstOrNull { it.id == state.currentGroupId.trim() }
            ?.isOwner()
        return resolveListNavigation(
            mode = state.mode,
            currentGroupId = state.currentGroupId,
            preferredTrackerId = preferredTrackerId,
            isCurrentGroupOwned = currentGroupOwned,
            isPreferredTrackerOwned = preferredTrackerOwned,
        )
    }

    internal fun onTrackerMarkerTapped(trackerId: String) {
        val rt = runtime ?: return
        val normalizedTrackerId = trackerId.trim()
        if (normalizedTrackerId.isEmpty()) return
        val snapshot = rt.renderEngine.buildCurrentSessionSnapshot()
        val state = snapshot.uiState
        val selection = buildSelectionCard(snapshot, normalizedTrackerId)
        if (selection == null) {
            rt.stateHub.uiStateMutable.value = state.withClearedMapSelectionCard()
            return
        }
        rt.stateHub.uiStateMutable.value = applySelectionCard(state, selection)
    }

    internal fun onMapBackgroundTapped(): Boolean {
        val rt = runtime ?: return false
        val state = rt.stateHub.uiStateMutable.value
        if (!resolveBackgroundTapShouldCloseBottomCard(
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
        val rt = runtime ?: return
        val selection = rt.stateHub.uiStateMutable.value.selectedMapTracker ?: return
        openTrackerOnMap(selection.trackerId, selection.trackerName)
    }

    internal fun toggleSelectedTrackerLock() {
        val rt = runtime ?: return
        val selection = rt.stateHub.uiStateMutable.value.selectedMapTracker ?: return
        toggleTrackerLock(selection.trackerId)
    }

    internal fun toggleDisplayedTrackerLock() {
        val rt = runtime ?: return
        val displayedId = rt.displayedTrackerId()
        if (displayedId.isEmpty()) return
        toggleTrackerLock(displayedId)
    }

    private fun toggleTrackerLock(trackerId: String) {
        val rt = runtime ?: return
        val selectedId = trackerId.trim()
        if (selectedId.isEmpty()) return
        val state = rt.stateHub.uiStateMutable.value
        val nextSelectionLock = if (state.selectionLockTrackerId == selectedId) "" else selectedId
        rt.stateHub.uiStateMutable.value = state.withAllMapLocksDisabled().copy(selectionLockTrackerId = nextSelectionLock)
    }

    internal fun selectionLockPointOrNull(): Pair<Double, Double>? {
        val rt = runtime ?: return null
        return selectionLockPointOrNull(rt.renderEngine.buildCurrentSessionSnapshot())
    }

    internal fun selectionLockPointOrNull(
        snapshot: TrackerMapSessionSnapshot
    ): Pair<Double, Double>? {
        val trackerId = snapshot.uiState.selectionLockTrackerId.trim()
        if (trackerId.isEmpty()) return null
        val point = resolveTrackerPointData(snapshot, trackerId) ?: return null
        return point.latitude to point.longitude
    }

    private fun buildSelectionCard(
        snapshot: TrackerMapSessionSnapshot,
        trackerId: String
    ): TrackerMapSelectionCard? {
        val rt = runtime ?: return null
        val state = snapshot.uiState
        val tracker = rt.catalog().trackers.firstOrNull { it.id == trackerId }
        val point = resolveTrackerPointData(snapshot, trackerId) ?: return null
        val trackerName = tracker?.name
            ?.takeIf { it.isNotBlank() }
            ?: state.displayedTrackerName.takeIf { trackerId == state.displayedTrackerId && it.isNotBlank() }
            ?: rt.catalogSelectedTrackerName().takeIf { trackerId == rt.catalogSelectedTrackerId() && it.isNotBlank() }
            ?: trackerId
        return TrackerMapSelectionCard(
            trackerId = trackerId,
            trackerName = trackerName,
            latitude = point.latitude,
            longitude = point.longitude,
            lastUpdatedMs = MapRenderMath.resolveLastReportedAtMs(
                trackerId = trackerId,
                recording = rt.recording(),
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
        val rt = runtime ?: return null
        val normalizedId = trackerId.trim()
        if (normalizedId.isEmpty()) return null
        val tracker = rt.catalog().trackers.firstOrNull { it.id == normalizedId }
        return MapRenderMath.resolveLastPoint(
            snapshot = snapshot,
            trackerId = trackerId,
            tracker = tracker,
        )
    }

    private fun applyMapContextTransition(
        contextOverrides: (TrackerMapUiState) -> TrackerMapUiState,
        pendingReopenTrackerId: String?,
        reloadReason: TrackerMapTrailReloadReason = TrackerMapTrailReloadReason.GenericMapRefresh,
        desiredSelectionLockTrackerId: String? = null,
    ) {
        val rt = runtime ?: return
        val preservedSingleTrackerId = if (reloadReason == TrackerMapTrailReloadReason.RestoreSelectedAfterStreaming) {
            pendingReopenTrackerId
        } else {
            null
        }
        rt.stateHub.uiStateMutable.update { latest ->
            val reset = resetMapContext(
                state = contextOverrides(latest),
                preservedSingleTrackerId = preservedSingleTrackerId,
                trails = rt.trailEngine.trail.value,
            )
            rt.trailEngine.resetGeometry(preservedSingle = reset.nextTrails.singleTrail)
            val next = reset.nextState
                .withAllMapLocksDisabled()
                .withClearedMapSelectionCard()
            if (desiredSelectionLockTrackerId != null) {
                next.copy(selectionLockTrackerId = desiredSelectionLockTrackerId)
            } else {
                next
            }
        }
        rt.renderEngine.resetLastResolution()
        rt.renderEngine.reprojectTrailsFromRepository("map_context_transition")
        pendingReopenSingleTrackerLoadId = pendingReopenTrackerId
        rt.trailEngine.invalidateLoadedSeed()
        rt.trailEngine.requestRuntimeTrailReload(reloadReason)
        refreshStreamTargets()
        send(MapSessionMailbox.ModeChanged(rt.stateHub.uiStateMutable.value.mode))
    }

    internal fun onHostPaused() {
        lastBackgroundAtElapsedMs = SystemClock.elapsedRealtime()
    }

    internal fun onHostResumed() {
        val rt = runtime ?: return
        rt.ports.viewModelScope.launch {
            if (rt.recomputeStaleRollingWindows()) {
                rt.renderEngine.publishRenderPackage()
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
        val rt = runtime ?: return
        mapSurfaceVisible = true
        if (!mapReady) {
            pendingResumeEvaluation = pendingResumeEvaluation ||
                pendingInitialTrackerForMap ||
                lastBackgroundAtElapsedMs > 0L
            return
        }
        evaluateResumeAfterBackground(allowZeroGap = pendingInitialTrackerForMap || pendingResumeEvaluation)
        rt.renderEngine.reprojectTrailsFromRepository("map_surface_visible")
        bumpReconcileToken()
        send(MapSessionMailbox.SurfaceShown)
    }

    internal fun onMapSurfaceHidden(markBackground: Boolean = false) {
        val rt = runtime ?: return
        mapSurfaceVisible = false
        mapReady = false
        if (markBackground) {
            lastBackgroundAtElapsedMs = SystemClock.elapsedRealtime()
            pendingResumeEvaluation = true
        }
        bumpReconcileToken()
        clearSessionRequests()
    }

    internal fun setMapReady(isReady: Boolean) {
        mapReady = isReady
        if (mapReady) {
            send(MapSessionMailbox.MapReady)
        }
        if (!mapReady || !pendingResumeEvaluation) return
        pendingResumeEvaluation = false
        evaluateResumeAfterBackground(allowZeroGap = pendingInitialTrackerForMap)
    }

    internal fun evaluateResumeAfterBackground(allowZeroGap: Boolean) {
        val rt = runtime ?: return
        val backgroundDurationMs = if (lastBackgroundAtElapsedMs > 0L) {
            SystemClock.elapsedRealtime() - lastBackgroundAtElapsedMs
        } else {
            0L
        }
        if (backgroundDurationMs <= 0L && !allowZeroGap) return
        val state = rt.stateHub.uiStateMutable.value
        val groupSelection = rt.resolveGroupModeSelection(state)
        val hasPendingInitialTracker = pendingInitialTrackerForMap
        val selectedTrackerId = rt.catalogSelectedTrackerId().trim()
        if (hasPendingInitialTracker &&
            state.mode == TrackerMapDisplayMode.SINGLE_SESSION &&
            selectedTrackerId.isBlank() &&
            rt.displayedTrackerId(state).isBlank()
        ) {
            pendingResumeEvaluation = true
            return
        }
        pendingInitialTrackerForMap = false
        val streamRuntime = rt.dependencies.liveStreamSubscriptionRepository.state.value
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
        val persistedStreamTargetIds = SharedPrefsLiveStreamPersistPort(rt.ports.application).read().first
            .ifEmpty { streamRuntime.mergedTargets }
        val unsanitizedResumeStreamTrackerIds = if (streamRuntime.activeTargets.isNotEmpty()) {
            streamRuntime.activeTargets
        } else {
            state.activeStreamedTrackerIds + persistedStreamTargetIds
        }
        val resumeStreamTrackerIds = StreamingTargetPolicy.normalizeTrackerIds(unsanitizedResumeStreamTrackerIds)
        val outcome = resolveReopen(
            TrackerMapResumeInput(
                trackingRunning = rt.recording().localRecordingActive,
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
                displayedTrackerId = rt.displayedTrackerId(state),
                hasTrailPoints = rt.trailEngine.trail.value.hasTrailPoints,
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
            refreshStreamTargets()
            bumpReconcileToken()
            lastBackgroundAtElapsedMs = 0L
            pendingResumeEvaluation = false
        }
    }

    internal fun streamingActiveTargetsMatchDisplayed(
        state: TrackerMapUiState,
        streamRuntime: LiveStreamSubscriptionState,
        groupSelection: com.geovault.tracker.presentation.TrackerMapGroupModeSelection,
    ): Boolean {
        val rt = runtime ?: return false
        return streamingActiveTargetsMatchDisplayed(
            mode = state.mode,
            displayedIds = when (state.mode) {
                TrackerMapDisplayMode.GROUP_PLACEHOLDER -> groupSelection.trackerIds
                TrackerMapDisplayMode.ALL_QUEUE -> rt.visibleMapRosterTrackerIds()
                else -> emptySet()
            },
            localRecordingActive = rt.recording().localRecordingActive,
            locallyRecordedTrackerId = rt.recording().locallyRecordedTrackerId,
            activeStreamTargets = streamRuntime.activeTargets,
        )
    }

    internal fun displayedRosterHasServerHistory(
        state: TrackerMapUiState,
        groupSelection: com.geovault.tracker.presentation.TrackerMapGroupModeSelection,
    ): Boolean {
        val rt = runtime ?: return false
        val rosterIds = when (state.mode) {
            TrackerMapDisplayMode.GROUP_PLACEHOLDER -> groupSelection.trackerIds
            TrackerMapDisplayMode.ALL_QUEUE -> rt.visibleMapRosterTrackerIds()
            else -> emptySet()
        }
        val normalizedRosterIds = rosterIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (normalizedRosterIds.isEmpty()) return false
        val trackers = rt.catalog().trackers
        val snapshots = rt.dependencies.historyRepository.snapshots.value
        return normalizedRosterIds.all { trackerId ->
            MapTrailEngine.hasAuthoritativeServerTrunk(snapshots, trackers, trackerId)
        }
    }

    private suspend fun applyReopenDecision(decision: TrackerMapResumeDecision) {
        val rt = runtime ?: return
        when (decision) {
            TrackerMapResumeDecision.NoOp -> Unit
            TrackerMapResumeDecision.MultiContextNoStreaming -> Unit
            is TrackerMapResumeDecision.StartMultiContextStreaming -> {
                pendingReopenSingleTrackerLoadId = null
                val locallyRecordedTrackerId = rt.recording().locallyRecordedTrackerId
                val ids = StreamingTargetPolicy.remoteSubscriptionTargets(
                    StreamingTargetPolicyInput(
                        requestedTrackerIds = decision.trackerIds,
                        locallyRecordedTrackerIds = setOfNotBlank(locallyRecordedTrackerId),
                    )
                )
                rt.trailEngine.replaceRemoteLastPoints(
                    filterRemoteLastPointsForAcceptedIds(
                        remoteLastPoints = rt.trailEngine.trail.value.remoteLastPoints,
                        acceptedRemoteTrackerIds = ids,
                    )
                )
                bumpReconcileToken()
            }
            TrackerMapResumeDecision.ClearSingleTrackerState -> {
                pendingReopenSingleTrackerLoadId = null
                rt.trailEngine.clearRemoteLastPoints()
                rt.stateHub.uiStateMutable.update { cur ->
                    cur.copy(
                        displayedTrackerId = "",
                        displayedTrackerName = "",
                    ).withAllMapLocksDisabled().withClearedMapSelectionCard()
                }
                markUserStopped()
                stopForegroundStreaming(rt.dependencies.liveStreamSubscriptionRepository)
            }
            is TrackerMapResumeDecision.LoadSingleTrackerRuntime,
            is TrackerMapResumeDecision.LoadSingleTrackerBootstrap -> {
                val trackerId = when (decision) {
                    is TrackerMapResumeDecision.LoadSingleTrackerRuntime -> decision.trackerId
                    is TrackerMapResumeDecision.LoadSingleTrackerBootstrap -> decision.trackerId
                }
                pendingReopenSingleTrackerLoadId = trackerId.takeIf { it.isNotBlank() }
                if (trackerId.isNotBlank()) {
                    val trackerName = if (trackerId == rt.catalogSelectedTrackerId()) {
                        rt.catalogSelectedTrackerName()
                    } else {
                        rt.stateHub.uiStateMutable.value.displayedTrackerName
                    }
                    val previousDisplayedTrackerId = rt.stateHub.uiStateMutable.value.displayedTrackerId.trim()
                    val trackerChanged = trackerId.trim() != previousDisplayedTrackerId
                    rt.stateHub.uiStateMutable.value = rt.stateHub.uiStateMutable.value.copy(
                        displayedTrackerId = trackerId,
                        displayedTrackerName = trackerName,
                    ).let { next ->
                        if (trackerChanged) next.withAllMapLocksDisabled() else next
                    }.withClearedMapSelectionCard()
                }
                rt.trailEngine.requestAndAwaitRuntimeTrailReload(TrackerMapTrailReloadReason.ExplicitTrackerLoad)
                if (pendingReopenSingleTrackerLoadId == trackerId) {
                    pendingReopenSingleTrackerLoadId = null
                }
                bumpReconcileToken()
            }
            TrackerMapResumeDecision.RestartDisplayedTrackerStreaming -> {
                pendingReopenSingleTrackerLoadId = null
                bumpReconcileToken()
            }
        }
    }

    internal fun setFollowLock(enabled: Boolean) {
        val rt = runtime ?: return
        val state = rt.stateHub.uiStateMutable.value
        rt.stateHub.uiStateMutable.value = if (enabled) {
            state.withAllMapLocksDisabled().copy(followLockEnabled = true)
        } else {
            state.copy(followLockEnabled = false)
        }
    }

    internal fun onUserOwnedZoom() {
        val rt = runtime ?: return
        rt.renderEngine.onUserOwnedZoom()
        rt.ports.viewModelScope.launch { rt.renderEngine.publishRenderPackage() }
    }

    internal fun disableAllMapLocks() {
        val rt = runtime ?: return
        rt.renderEngine.onUserGestureStarted()
        val state = rt.stateHub.uiStateMutable.value
        if (!state.hasAnyMapLockActive()) {
            return
        }
        rt.stateHub.uiStateMutable.value = state.withAllMapLocksDisabled()
    }

    internal fun setLiveActiveFit(enabled: Boolean) {
        val rt = runtime ?: return
        val state = rt.stateHub.uiStateMutable.value
        rt.stateHub.uiStateMutable.value = if (enabled) {
            if (MapRenderMath.composesWithSelectionLock(state.mode)) {
                state.copy(followLockEnabled = false, liveActiveFitEnabled = true)
            } else {
                state.withAllMapLocksDisabled().copy(liveActiveFitEnabled = true)
            }
        } else {
            state.copy(liveActiveFitEnabled = false)
        }
        if (enabled) {
            rt.ports.viewModelScope.launch { rt.renderEngine.publishRenderPackage() }
            requestFitTrail()
        }
    }

    internal fun requestFitTrail(mode: TrackerMapFitTrailMode = TrackerMapFitTrailMode.Animated) {
        val rt = runtime ?: return
        rt.renderEngine.requestExplicitFit(rt.renderEngine.trailBoundsOrNull(), mode)
    }

    internal fun stateWithRefreshedSelectionCard(
        state: TrackerMapUiState,
        changedTrackerId: String,
    ): TrackerMapUiState {
        val rt = runtime ?: return state
        val selection = state.selectedMapTracker ?: return state
        if (!state.isBottomCardVisible || selection.trackerId != changedTrackerId.trim()) return state
        val refreshed = buildSelectionCard(rt.renderEngine.buildSessionSnapshotForState(state), selection.trackerId) ?: return state
        return state.copy(selectedMapTracker = refreshed)
    }

    private fun planSignature(state: TrackerMapUiState): String {
        val recording = runtime?.recording() ?: state.runtime
        val selectedTrackerId = runtime?.catalogSelectedTrackerId().orEmpty()
        return buildString {
            append(state.mode)
            append('|').append(state.displayedTrackerId.trim())
            append('|').append(state.displayedTrackerName.trim())
            append('|').append(state.currentGroupId.trim())
            append('|').append(selectedTrackerId.trim())
            append('|').append(recording.localRecordingActive)
            append('|').append(recording.locallyRecordedTrackerId.trim())
            append('|').append(state.renderMetadataSignature)
        }
    }

    private fun emptyPlan(state: TrackerMapUiState): TrackerMapStreamingPlan {
        return TrackerMapStreamingPlan(
            mode = state.mode,
            selectedTrackerId = runtime?.catalogSelectedTrackerId().orEmpty(),
            displayedTrackerId = state.displayedTrackerId,
            displayedTrackerName = state.displayedTrackerName,
            resolvedGroupId = state.currentGroupId,
            groupTrackerIds = emptySet(),
            visibleRosterTrackerIds = emptySet(),
            locallyRecordedTrackerIds = emptySet(),
            remoteSubscriptionIds = emptySet(),
            acceptedRemoteTrackerIds = emptySet(),
            localOverlayTrackerIds = emptySet(),
            trailReloadPlan = com.geovault.tracker.presentation.TrackerMapTrailReloadPlan(
                source = com.geovault.tracker.presentation.TrackerMapTrailSource.SINGLE_QUEUE,
                singleTrackerId = state.displayedTrackerId,
                activeTrackerId = state.displayedTrackerId,
            ),
        )
    }

    private data class ReconcileInputs(
        val state: TrackerMapUiState,
        val streamRuntime: LiveStreamSubscriptionState,
        val token: Long,
    )

    enum class HistoryClearRefreshAction {
        REFRESH_GROUP_OR_ALL,
        REFRESH_DISPLAYED_SINGLE,
        REFRESH_SELECTED_SINGLE,
        NO_OP,
    }

    companion object {
        private val ROLLING_WINDOW_RECOMPUTE_INTERVAL_MS = TimeUnit.SECONDS.toMillis(60)
        internal const val SESSION_REQUEST_DEDUPE_WINDOW_MS = 4_000L

        internal fun <V> invalidateSessionRequests(
            flight: TimeWindowedSingleFlight<String, V>,
            trackerId: String,
        ) {
            val id = trackerId.trim()
            if (id.isEmpty()) return
            flight.invalidateWhere { cacheKeyReferencesTrackerId(it, id) }
        }

        internal fun cacheKeyReferencesTrackerId(key: String, trackerId: String): Boolean {
            var index = 0
            while (true) {
                val found = key.indexOf(trackerId, startIndex = index)
                if (found < 0) return false
                val precedingChar = key.getOrNull(found - 1)
                val followingChar = key.getOrNull(found + trackerId.length)
                val startsBoundary = precedingChar == null || precedingChar == ':' || precedingChar == ','
                val endsBoundary = followingChar == null || followingChar == ','
                if (startsBoundary && endsBoundary) return true
                index = found + 1
            }
        }

        fun project(input: TrackerMapSessionIntent): TrackerMapStreamingPlan {
            val selectedTrackerId = input.selectedTrackerId.trim()
            val runtimeRunning = input.runtime.localRecordingActive
            val locallyRecordedTrackerId = input.runtime.locallyRecordedTrackerId
            val displayedTrackerId = input.displayedTrackerId.trim().ifBlank { selectedTrackerId }
            val displayedTrackerName = input.displayedTrackerName.trim().ifBlank {
                input.selectedTrackerName.trim()
            }
            val groupTrackerIds = StreamingTargetPolicy.normalizeTrackerIds(input.groupSelection.trackerIds)
            val rosterTrackerIds = StreamingTargetPolicy.normalizeTrackerIds(input.rosterTrackerIds)
            val localTrackerIds = if (locallyRecordedTrackerId.isNotEmpty()) {
                setOf(locallyRecordedTrackerId)
            } else {
                emptySet()
            }
            val requestedRemoteTrackerIds: Set<String> = when (input.mode) {
                TrackerMapDisplayMode.SINGLE_SESSION -> {
                    val id = displayedTrackerId
                    when {
                        id.isEmpty() -> emptySet()
                        StreamingTargetPolicy.isHistoryOnlyView(id, selectedTrackerId) -> emptySet()
                        else -> setOf(id)
                    }
                }
                TrackerMapDisplayMode.GROUP_PLACEHOLDER -> groupTrackerIds
                TrackerMapDisplayMode.ALL_QUEUE -> rosterTrackerIds
            }
            val remoteSubscriptionIds = StreamingTargetPolicy.remoteSubscriptionTargets(
                StreamingTargetPolicyInput(
                    requestedTrackerIds = requestedRemoteTrackerIds,
                    locallyRecordedTrackerIds = localTrackerIds,
                )
            )
            val acceptedRemoteTrackerIds = remoteSubscriptionIds
            val localOverlayTrackerIds = when {
                localTrackerIds.isEmpty() -> emptySet()
                input.mode == TrackerMapDisplayMode.ALL_QUEUE -> localTrackerIds
                input.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER && locallyRecordedTrackerId in groupTrackerIds -> localTrackerIds
                input.mode == TrackerMapDisplayMode.SINGLE_SESSION &&
                    (displayedTrackerId.isEmpty() || displayedTrackerId == locallyRecordedTrackerId) -> localTrackerIds
                else -> emptySet()
            }
            val trailReloadPlan = MapTrailEngine.resolveReloadPlan(
                TrackerMapTrailReloadInput(
                    mode = input.mode,
                    runtimeRunning = runtimeRunning,
                    selectedTrackerId = selectedTrackerId,
                    locallyRecordedTrackerId = locallyRecordedTrackerId,
                    activeTrackerId = displayedTrackerId,
                    rosterTrackerIds = rosterTrackerIds,
                    groupSelection = input.groupSelection,
                )
            )
            return TrackerMapStreamingPlan(
                mode = input.mode,
                selectedTrackerId = selectedTrackerId,
                displayedTrackerId = displayedTrackerId,
                displayedTrackerName = displayedTrackerName,
                resolvedGroupId = if (input.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
                    input.groupSelection.groupId.orEmpty()
                } else {
                    ""
                },
                groupTrackerIds = groupTrackerIds,
                visibleRosterTrackerIds = rosterTrackerIds,
                locallyRecordedTrackerIds = localTrackerIds,
                remoteSubscriptionIds = remoteSubscriptionIds,
                acceptedRemoteTrackerIds = acceptedRemoteTrackerIds,
                localOverlayTrackerIds = localOverlayTrackerIds,
                trailReloadPlan = trailReloadPlan,
            )
        }

        fun resolveHistoryClearRefreshAction(
            mode: TrackerMapDisplayMode,
            displayedTrackerId: String,
            selectedTrackerId: String,
            clearedTrackerId: String,
        ): HistoryClearRefreshAction {
            if (mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER || mode == TrackerMapDisplayMode.ALL_QUEUE) {
                return HistoryClearRefreshAction.REFRESH_GROUP_OR_ALL
            }
            val normalizedDisplayed = displayedTrackerId.trim()
            val normalizedSelected = selectedTrackerId.trim()
            val normalizedCleared = clearedTrackerId.trim()
            if (normalizedCleared.isEmpty()) return HistoryClearRefreshAction.NO_OP
            if (normalizedDisplayed.isNotEmpty() && normalizedDisplayed == normalizedCleared) {
                return HistoryClearRefreshAction.REFRESH_DISPLAYED_SINGLE
            }
            if (normalizedDisplayed.isEmpty() && normalizedSelected == normalizedCleared) {
                return HistoryClearRefreshAction.REFRESH_SELECTED_SINGLE
            }
            return HistoryClearRefreshAction.NO_OP
        }

        fun resolveStreamTargetIds(
            mode: TrackerMapDisplayMode,
            runtimeRunning: Boolean,
            selectedTrackerId: String,
            displayedTrackerId: String,
            rosterTrackerIds: Set<String>,
            groupTrackerIds: Set<String> = emptySet(),
            groupId: String? = null,
        ): Set<String> {
            return project(
                TrackerMapSessionIntent(
                    mode = mode,
                    runtime = TrackingRuntimeSnapshot(
                        isRunning = runtimeRunning,
                        recordingRuntime = RecordingRuntime(
                            sessionActive = runtimeRunning,
                            selectedTrackerId = selectedTrackerId,
                        ),
                    ),
                    selectedTrackerId = selectedTrackerId,
                    displayedTrackerId = displayedTrackerId,
                    displayedTrackerName = "",
                    rosterTrackerIds = rosterTrackerIds,
                    groupSelection = TrackerMapGroupModeSelection(groupId = groupId, trackerIds = groupTrackerIds),
                    activeStreamedTrackerIds = emptySet(),
                ),
            ).remoteSubscriptionIds
        }

        fun resolveLiveHeadCoord(
            state: TrackerMapUiState,
            selectedTrackerId: String = "",
            singleTrail: List<QueuedLocation> = emptyList(),
            allQueueTrailsByTracker: Map<String, List<QueuedLocation>> = emptyMap(),
            remoteLastPoints: Map<String, TrackPoint> = emptyMap(),
        ): Pair<Double, Double>? {
            val displayedTrackerId = TrackerMapDisplayIds.effectiveDisplayedTrackerId(
                state.displayedTrackerId,
                selectedTrackerId.ifBlank { state.runtime.locallyRecordedTrackerId },
            )
            val resolved = MapRenderMath.resolveLastPoint(
                trackerId = displayedTrackerId,
                liveHeads = MapRenderMath.liveHeadsIncludingRecording(remoteLastPoints, state.runtime),
            ) ?: return null
            return resolved.latitude to resolved.longitude
        }

        fun allQueueTrailsWithLocalRuntimeOverlay(
            mode: TrackerMapDisplayMode,
            runtime: TrackingRuntimeSnapshot,
            groupTrackerIds: Set<String>,
            allQueueTrailsByTracker: Map<String, List<QueuedLocation>>,
        ): Map<String, List<QueuedLocation>> {
            return MapRenderMath.allQueueTrailsWithLocalRuntimeOverlay(
                mode = mode,
                runtime = runtime,
                groupTrackerIds = groupTrackerIds,
                allQueueTrailsByTracker = allQueueTrailsByTracker,
                trailPointLimit = TrackerMapViewModel.TRAIL_POINT_LIMIT,
            )
        }

        fun singleTrailWithLocalRuntimeOverlay(
            mode: TrackerMapDisplayMode,
            runtime: TrackingRuntimeSnapshot,
            displayedTrackerId: String,
            trail: List<QueuedLocation>,
        ): List<QueuedLocation> {
            return MapRenderMath.singleTrailWithLocalRuntimeOverlay(
                mode = mode,
                runtime = runtime,
                displayedTrackerId = displayedTrackerId,
                trail = trail,
                trailPointLimit = TrackerMapViewModel.TRAIL_POINT_LIMIT,
            )
        }

        fun shouldShowBatteryOptimizationHint(
            wantsSubscription: Boolean,
            connectionHealthy: Boolean,
            unhealthySinceMs: Long?,
            nowMs: Long,
            hasUsableNetwork: Boolean,
            hasBatteryOptimizationExemption: Boolean,
        ): Boolean {
            if (!wantsSubscription || connectionHealthy || hasBatteryOptimizationExemption || !hasUsableNetwork) {
                return false
            }
            val unhealthySince = unhealthySinceMs ?: return false
            return nowMs - unhealthySince >= StreamingConfig.batteryOptimizationHintUnhealthyThresholdMs
        }

        fun resolveListNavigation(
            mode: TrackerMapDisplayMode,
            currentGroupId: String,
            preferredTrackerId: String?,
            isCurrentGroupOwned: Boolean?,
            isPreferredTrackerOwned: Boolean?,
        ): MapListNavigationTarget {
            val normalizedGroupId = currentGroupId.trim()
            val normalizedTrackerId = preferredTrackerId?.trim().orEmpty().ifBlank { null }
            if (mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER && normalizedGroupId.isNotEmpty()) {
                return if (isCurrentGroupOwned == true) {
                    MapListNavigationTarget(
                        destination = MapListNavigationDestination.GROUPS,
                        trackerId = normalizedTrackerId,
                        groupId = normalizedGroupId,
                    )
                } else {
                    MapListNavigationTarget(
                        destination = MapListNavigationDestination.SHARED,
                        trackerId = normalizedTrackerId,
                        groupId = normalizedGroupId,
                    )
                }
            }
            if (normalizedTrackerId != null && isPreferredTrackerOwned == false) {
                return MapListNavigationTarget(
                    destination = MapListNavigationDestination.SHARED,
                    trackerId = normalizedTrackerId,
                )
            }
            return MapListNavigationTarget(
                destination = MapListNavigationDestination.TRACKERS,
                trackerId = normalizedTrackerId,
            )
        }

        fun streamingActiveTargetsMatchDisplayed(
            mode: TrackerMapDisplayMode,
            displayedIds: Set<String>,
            localRecordingActive: Boolean,
            locallyRecordedTrackerId: String,
            activeStreamTargets: Set<String>,
        ): Boolean {
            if (mode != TrackerMapDisplayMode.GROUP_PLACEHOLDER && mode != TrackerMapDisplayMode.ALL_QUEUE) {
                return false
            }
            val excluded = if (localRecordingActive && locallyRecordedTrackerId.isNotBlank()) {
                setOf(locallyRecordedTrackerId.trim())
            } else {
                emptySet()
            }
            val expected = StreamingTargetPolicy.normalizeTrackerIds(displayedIds - excluded)
            if (expected.isEmpty()) return false
            val active = StreamingTargetPolicy.normalizeTrackerIds(activeStreamTargets)
            return active == expected
        }

        fun displayedRosterHasServerHistory(
            mode: TrackerMapDisplayMode,
            rosterIds: Set<String>,
            allQueueTrailsByTracker: Map<String, List<QueuedLocation>>,
        ): Boolean {
            if (mode != TrackerMapDisplayMode.GROUP_PLACEHOLDER && mode != TrackerMapDisplayMode.ALL_QUEUE) {
                return false
            }
            val normalizedRosterIds = rosterIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            if (normalizedRosterIds.isEmpty()) return false
            return normalizedRosterIds.all { id ->
                allQueueTrailsByTracker[id].orEmpty().any(MapTrailEngine::isServerHistory)
            }
        }

        fun resolveBottomCardVisibilityForMarkerTap(hasSelectionCard: Boolean): Boolean {
            return hasSelectionCard
        }

        fun resolveBackgroundTapShouldCloseBottomCard(
            isBottomCardVisible: Boolean,
            hasSelectionCard: Boolean,
        ): Boolean {
            return isBottomCardVisible || hasSelectionCard
        }

        fun resolveRenderSelectedMapTrackerId(
            isBottomCardVisible: Boolean,
            selectedMapTrackerId: String?,
        ): String? {
            return selectedMapTrackerId
                ?.trim()
                ?.takeIf { isBottomCardVisible && it.isNotEmpty() }
        }

        fun resolveFocusActionVisible(mode: TrackerMapDisplayMode): Boolean {
            return mode != TrackerMapDisplayMode.SINGLE_SESSION
        }

        fun filterRemoteLastPointsForAcceptedIds(
            remoteLastPoints: Map<String, TrackPoint>,
            acceptedRemoteTrackerIds: Set<String>,
        ): Map<String, TrackPoint> {
            val acceptedIds = acceptedRemoteTrackerIds
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
            if (acceptedIds.isEmpty()) return remoteLastPoints
            return remoteLastPoints.filterKeys { it.trim() in acceptedIds }
        }

        fun resetMapContext(
            state: TrackerMapUiState,
            preservedSingleTrackerId: String? = null,
            trails: TrailView = TrailView(),
        ): TrackerMapContextReset {
            val preservedTrail = preservedSingleTrackerTrail(state, trails, preservedSingleTrackerId)
            val preservedId = preservedSingleTrackerId?.trim().orEmpty()
            return TrackerMapContextReset(
                nextState = state,
                nextTrails = TrailView(
                    singleTrail = preservedTrail,
                    snapshot = trails.snapshot,
                    degradedTrackerIds = trails.degradedTrackerIds.filter { it == preservedId }.toSet(),
                ),
            )
        }

        private fun preservedSingleTrackerTrail(
            state: TrackerMapUiState,
            trails: TrailView,
            trackerId: String?,
        ): List<QueuedLocation> {
            val normalizedTrackerId = trackerId?.trim().orEmpty()
            if (normalizedTrackerId.isEmpty()) return emptyList()
            val multiTrail = trails.tracksByTrackerId[normalizedTrackerId].orEmpty()
            if (multiTrail.isNotEmpty()) return multiTrail
            val matchingSingleTrail = trails.singleTrail.filter { it.trackerId.trim() == normalizedTrackerId }
            if (matchingSingleTrail.isNotEmpty()) return matchingSingleTrail
            val displayedTrackerId = state.displayedTrackerId.trim()
            return if (displayedTrackerId == normalizedTrackerId) {
                trails.singleTrail
            } else {
                emptyList()
            }
        }

        internal fun hasMapStreamingLease(repository: LiveStreamSubscriptionRepository): Boolean {
            return repository.state.value.leases[StreamingOwner.MAP] != null
        }

        internal fun consumeStoppedMapStreamingLease(repository: LiveStreamSubscriptionRepository): Boolean {
            val had = hasMapStreamingLease(repository)
            if (had) repository.setLease(StreamingOwner.MAP, null)
            return had
        }

        internal fun stopForegroundStreaming(repository: LiveStreamSubscriptionRepository) {
            repository.setLease(StreamingOwner.MAP, null)
        }

        internal fun applyMapLease(
            repository: LiveStreamSubscriptionRepository,
            mode: TrackerMapDisplayMode,
            remoteSubscriptionIds: Set<String>,
            locallyRecordedTrackerId: String,
            effectiveDisplayedId: String,
            effectiveDisplayedName: String,
        ) {
            val command = resolveStreamingCommand(
                TrackerMapStreamingDecisionInput(
                    mode = mode,
                    remoteSubscriptionIds = remoteSubscriptionIds,
                    displayedTrackerId = effectiveDisplayedId,
                    displayedTrackerName = effectiveDisplayedName,
                )
            )
            when (command) {
                is TrackerMapStreamingCommand.Start -> {
                    repository.setLease(
                        StreamingOwner.MAP,
                        StreamIntent(
                            trackerIds = command.trackerIds,
                            displayName = command.trackerName,
                            locallyRecordedTrackerId = locallyRecordedTrackerId,
                        ),
                    )
                }
                TrackerMapStreamingCommand.Stop -> {
                    val historyOnlyHold = mode == TrackerMapDisplayMode.SINGLE_SESSION &&
                        effectiveDisplayedId.trim().isNotEmpty()
                    if (historyOnlyHold && !hasMapStreamingLease(repository)) {
                        return
                    }
                    repository.setLease(StreamingOwner.MAP, null)
                }
                TrackerMapStreamingCommand.NoOp -> Unit
            }
        }

        fun resolveStreamingCommand(input: TrackerMapStreamingDecisionInput): TrackerMapStreamingCommand {
            if (input.mode == TrackerMapDisplayMode.SINGLE_SESSION &&
                input.displayedTrackerId.trim().isEmpty()
            ) {
                return TrackerMapStreamingCommand.NoOp
            }
            val ids = StreamingTargetPolicy.normalizeTrackerIds(input.remoteSubscriptionIds)
            if (ids.isEmpty()) return TrackerMapStreamingCommand.Stop
            val trackerName = if (ids.size == 1) {
                input.displayedTrackerName.trim().ifBlank { null }
            } else {
                null
            }
            return TrackerMapStreamingCommand.Start(
                trackerIds = ids,
                trackerName = trackerName
            )
        }

        fun resolveResume(input: TrackerMapResumeInput): TrackerMapResumeDecision {
            if (!input.mapReady) return TrackerMapResumeDecision.NoOp

            if (input.trackingRunning) {
                val selectedTrackerId = input.selectedTrackerId.takeIf { it.isNotBlank() }
                val streamedSanitized = input.activeStreamedTrackerIds.filterTo(mutableSetOf()) { id ->
                    id.isNotBlank()
                }
                if (input.mapViewContext == TrackerMapViewContext.GROUP || input.showAllTrackers) {
                    val fallbackGroupIds = input.currentGroupTrackIds.filterTo(mutableSetOf()) { id ->
                        id.isNotBlank()
                    }
                    return when {
                        streamedSanitized.isNotEmpty() ->
                            TrackerMapResumeDecision.StartMultiContextStreaming(streamedSanitized)
                        input.mapViewContext == TrackerMapViewContext.GROUP && fallbackGroupIds.isNotEmpty() ->
                            TrackerMapResumeDecision.StartMultiContextStreaming(fallbackGroupIds)
                        !selectedTrackerId.isNullOrEmpty() ->
                            TrackerMapResumeDecision.LoadSingleTrackerRuntime(selectedTrackerId)
                        else -> TrackerMapResumeDecision.MultiContextNoStreaming
                    }
                }

                val displayedTrackerId = input.displayedTrackerId.takeIf { it.isNotBlank() }
                val activeSingleTrackerId = displayedTrackerId ?: selectedTrackerId.orEmpty()
                if (activeSingleTrackerId.isEmpty() && !input.hasPendingInitialTracker) {
                    return TrackerMapResumeDecision.ClearSingleTrackerState
                }
                if (input.hasTrailPoints && displayedTrackerId == null && !selectedTrackerId.isNullOrEmpty()) {
                    return TrackerMapResumeDecision.NoOp
                }
                if (input.hasTrailPoints && displayedTrackerId == activeSingleTrackerId) {
                    return TrackerMapResumeDecision.NoOp
                }
                if (displayedTrackerId != null && displayedTrackerId in streamedSanitized) {
                    return TrackerMapResumeDecision.RestartDisplayedTrackerStreaming
                }
                if (activeSingleTrackerId.isNotEmpty() && activeSingleTrackerId == selectedTrackerId && input.hasPendingInitialTracker) {
                    return TrackerMapResumeDecision.LoadSingleTrackerRuntime(activeSingleTrackerId)
                }
                return TrackerMapResumeDecision.NoOp
            }

            if (input.mapViewContext == TrackerMapViewContext.GROUP || input.showAllTrackers) {
                val streamedSanitized = input.activeStreamedTrackerIds.filterTo(mutableSetOf()) { id ->
                    id.isNotBlank()
                }
                val groupIdsSanitized = input.currentGroupTrackIds.filterTo(mutableSetOf()) { id ->
                    id.isNotBlank()
                }
                return when {
                    streamedSanitized.isNotEmpty() ->
                        TrackerMapResumeDecision.StartMultiContextStreaming(streamedSanitized)
                    input.mapViewContext == TrackerMapViewContext.GROUP && groupIdsSanitized.isNotEmpty() ->
                        TrackerMapResumeDecision.StartMultiContextStreaming(groupIdsSanitized)
                    else -> TrackerMapResumeDecision.MultiContextNoStreaming
                }
            }

            val activeTrackerId = if (input.trackingRunning) {
                input.selectedTrackerId
            } else {
                input.displayedTrackerId.takeIf { it.isNotBlank() } ?: input.selectedTrackerId
            }
            if (activeTrackerId.isEmpty() && !input.hasPendingInitialTracker) {
                return TrackerMapResumeDecision.ClearSingleTrackerState
            }
            val displayedTrackerId = input.displayedTrackerId.takeIf { it.isNotBlank() }
            if (activeTrackerId.isNotEmpty() && displayedTrackerId != activeTrackerId) {
                if (!input.hasPendingInitialTracker) {
                    return TrackerMapResumeDecision.RestartDisplayedTrackerStreaming
                }
                val isStreamBootstrap = activeTrackerId in input.activeStreamedTrackerIds
                return if (isStreamBootstrap) {
                    TrackerMapResumeDecision.LoadSingleTrackerBootstrap(activeTrackerId)
                } else {
                    TrackerMapResumeDecision.LoadSingleTrackerRuntime(activeTrackerId)
                }
            }
            if (!input.hasTrailPoints && activeTrackerId.isNotEmpty()) {
                if (!input.hasPendingInitialTracker) {
                    return TrackerMapResumeDecision.RestartDisplayedTrackerStreaming
                }
                val isStreamBootstrap = activeTrackerId in input.activeStreamedTrackerIds
                return if (isStreamBootstrap) {
                    TrackerMapResumeDecision.LoadSingleTrackerBootstrap(activeTrackerId)
                } else {
                    TrackerMapResumeDecision.LoadSingleTrackerRuntime(activeTrackerId)
                }
            }
            return TrackerMapResumeDecision.RestartDisplayedTrackerStreaming
        }

        fun resolveReopen(input: TrackerMapResumeInput): TrackerMapReopenOutcome {
            val decision = resolveResume(input)
            return TrackerMapReopenOutcome(
                decision = decision,
                invariants = buildReopenInvariants(input, decision)
            )
        }

        internal fun decideRuntimeResync(
            previousIsRunning: Boolean?,
            currentIsRunning: Boolean,
            mapReady: Boolean,
            isGroup: Boolean,
        ): TrackerMapRuntimeResyncDecision {
            val transition = when {
                previousIsRunning == null -> TrackerMapRuntimeTransition.NONE
                !previousIsRunning && currentIsRunning -> TrackerMapRuntimeTransition.STARTED
                previousIsRunning && !currentIsRunning -> TrackerMapRuntimeTransition.STOPPED
                else -> TrackerMapRuntimeTransition.NONE
            }
            return when (transition) {
                TrackerMapRuntimeTransition.STARTED -> TrackerMapRuntimeResyncDecision(
                    transition = transition,
                    restartTrackPointStream = true,
                    restartDisplayedStreaming = mapReady,
                )
                TrackerMapRuntimeTransition.STOPPED,
                TrackerMapRuntimeTransition.NONE -> TrackerMapRuntimeResyncDecision(
                    transition = transition,
                    restartTrackPointStream = false,
                    restartDisplayedStreaming = false
                )
            }
        }

        private fun buildReopenInvariants(
            input: TrackerMapResumeInput,
            decision: TrackerMapResumeDecision,
        ): List<TrackerMapRuntimeInvariantStatus> {
            val selectedTrackerPresent = input.selectedTrackerId.isNotBlank()
            val trackingWithPointsAvoidsDestructiveReload = !(
                input.trackingRunning &&
                    input.hasTrailPoints &&
                    (decision is TrackerMapResumeDecision.LoadSingleTrackerRuntime ||
                        decision is TrackerMapResumeDecision.LoadSingleTrackerBootstrap ||
                        decision is TrackerMapResumeDecision.ClearSingleTrackerState)
                )
            val singleLoadIdempotent = when (decision) {
                is TrackerMapResumeDecision.LoadSingleTrackerRuntime -> decision.trackerId.isNotBlank()
                is TrackerMapResumeDecision.LoadSingleTrackerBootstrap -> decision.trackerId.isNotBlank()
                else -> true
            }
            return listOf(
                TrackerMapRuntimeInvariantStatus(
                    invariant = TrackerMapRuntimeInvariant.TRACKING_REQUIRES_SELECTED_TRACKER,
                    satisfied = !input.trackingRunning || selectedTrackerPresent,
                    details = "trackingRunning=${input.trackingRunning} selectedTrackerPresent=$selectedTrackerPresent",
                ),
                TrackerMapRuntimeInvariantStatus(
                    invariant = TrackerMapRuntimeInvariant.TRACKING_WITH_POINTS_MUST_NOT_FORCE_DESTRUCTIVE_RELOAD,
                    satisfied = trackingWithPointsAvoidsDestructiveReload,
                    details = "trackingRunning=${input.trackingRunning} hasTrackPoints=${input.hasTrailPoints} decision=$decision",
                ),
                TrackerMapRuntimeInvariantStatus(
                    invariant = TrackerMapRuntimeInvariant.SINGLE_LOAD_COMMANDS_MUST_BE_IDEMPOTENT,
                    satisfied = singleLoadIdempotent,
                    details = "decision=$decision",
                ),
            )
        }

        fun applyRosterRemoval(
            state: TrackerMapUiState,
            removedTrackerId: String,
            mapLeaseIds: Set<String> = emptySet(),
            trails: TrailView = TrailView(),
        ): TrackerRosterRemovalOutcome {
            val id = removedTrackerId.trim()
            if (id.isEmpty()) {
                return TrackerRosterRemovalOutcome(
                    state,
                    changed = false,
                    shouldRefreshStreamTargets = false,
                    nextTrails = trails,
                )
            }
            val wasDisplayed = state.displayedTrackerId.trim() == id
            val wasStreamed = id in mapLeaseIds || id in state.activeStreamedTrackerIds
            val hadTrailData = id in trails.tracksByTrackerId ||
                id in trails.remoteLastPoints ||
                trails.singleTrail.any { it.trackerId.trim() == id }
            val hadSelection = state.selectionLockTrackerId.trim() == id ||
                state.selectedMapTracker?.trackerId == id
            if (!wasDisplayed && !wasStreamed && !hadTrailData && !hadSelection) {
                return TrackerRosterRemovalOutcome(
                    state,
                    changed = false,
                    shouldRefreshStreamTargets = false,
                    nextTrails = trails,
                )
            }

            var next = state.copy(
                activeStreamedTrackerIds = state.activeStreamedTrackerIds - id,
            )
            val nextTrails = trails.withoutTracker(
                trackerId = id,
                clearSingleTrail = wasDisplayed && state.mode == TrackerMapDisplayMode.SINGLE_SESSION,
            )

            if (wasDisplayed) {
                val removedName = state.displayedTrackerName.trim()
                next = next.copy(
                    displayedTrackerId = "",
                    displayedTrackerName = "",
                    unavailableTrackerNotice = com.geovault.tracker.presentation.TrackerMapUnavailableNotice(
                        trackerId = id,
                        trackerName = removedName,
                    ),
                )
            }

            if (state.selectionLockTrackerId.trim() == id) {
                next = next.withAllMapLocksDisabled()
            }
            if (state.selectedMapTracker?.trackerId == id) {
                next = next.withClearedMapSelectionCard()
            }

            return TrackerRosterRemovalOutcome(
                nextState = next,
                changed = true,
                shouldRefreshStreamTargets = wasStreamed,
                nextTrails = nextTrails,
            )
        }

        fun applySelectionCard(
            state: TrackerMapUiState,
            selection: TrackerMapSelectionCard,
        ): TrackerMapUiState {
            val previousSelectionLockId = state.selectionLockTrackerId.trim()
            val nextSelectionLockId = previousSelectionLockId
                .takeIf { it.isNotEmpty() && it == selection.trackerId }
                .orEmpty()
            val droppedMismatchedLock = previousSelectionLockId.isNotEmpty() && nextSelectionLockId.isEmpty()
            return state.copy(
                isBottomCardVisible = resolveBottomCardVisibilityForMarkerTap(hasSelectionCard = true),
                selectedMapTracker = selection,
                selectionLockTrackerId = nextSelectionLockId,
                liveActiveFitEnabled = if (droppedMismatchedLock) false else state.liveActiveFitEnabled,
            )
        }

        fun resolveAutoLockOnRecordingStart(
            mode: TrackerMapDisplayMode,
            displayedTrackerId: String,
            selectedTrackerId: String,
        ): TrackerMapAutoLockOnRecordingResult {
            val lockId = displayedTrackerId.trim().ifBlank { selectedTrackerId.trim() }
            return when (mode) {
                TrackerMapDisplayMode.SINGLE_SESSION ->
                    if (lockId.isEmpty()) {
                        TrackerMapAutoLockOnRecordingResult.None
                    } else {
                        TrackerMapAutoLockOnRecordingResult.SelectionLock(lockId)
                    }
                TrackerMapDisplayMode.ALL_QUEUE,
                TrackerMapDisplayMode.GROUP_PLACEHOLDER ->
                    TrackerMapAutoLockOnRecordingResult.LiveActiveFit
            }
        }

        fun resolveAutoSelectionLockForSingleStream(
            mode: TrackerMapDisplayMode,
            previousTargets: Set<String>,
            nextTargets: Set<String>,
            displayedTrackerId: String,
        ): String? {
            if (mode != TrackerMapDisplayMode.SINGLE_SESSION) return null
            if (previousTargets == nextTargets) return null
            if (nextTargets.size != 1) return null
            val only = nextTargets.first().trim()
            if (only.isEmpty()) return null
            if (only != displayedTrackerId.trim()) return null
            return only
        }

        fun resolveEligibleGroups(
            groups: List<Group>,
            hiddenGroupIds: Set<String>,
            hiddenTrackIds: Set<String>,
            hiddenOwnerTrackerIds: Set<String>,
            rosterTrackerIds: Set<String>,
        ): List<TrackerMapGroupModeOption> {
            return eligibleGroups(
                groups = groups,
                hiddenGroupIds = hiddenGroupIds,
                hiddenTrackIds = hiddenTrackIds,
                hiddenOwnerTrackerIds = hiddenOwnerTrackerIds,
                rosterTrackerIds = rosterTrackerIds,
            ).map { (group, ids) ->
                TrackerMapGroupModeOption(
                    groupId = group.id,
                    groupName = group.name,
                    trackerIds = ids,
                )
            }
        }

        fun resolveGroupSelection(
            groups: List<Group>,
            hiddenGroupIds: Set<String>,
            hiddenTrackIds: Set<String>,
            hiddenOwnerTrackerIds: Set<String>,
            rosterTrackerIds: Set<String>,
            preferredGroupId: String?,
            preferredTrackerId: String?,
        ): TrackerMapGroupModeSelection {
            val eligibleGroups = eligibleGroups(
                groups = groups,
                hiddenGroupIds = hiddenGroupIds,
                hiddenTrackIds = hiddenTrackIds,
                hiddenOwnerTrackerIds = hiddenOwnerTrackerIds,
                rosterTrackerIds = rosterTrackerIds,
            )
            if (eligibleGroups.isEmpty()) {
                return TrackerMapGroupModeSelection(groupId = null, trackerIds = emptySet())
            }

            val preferredGroup = preferredGroupId?.trim().orEmpty()
            if (preferredGroup.isNotEmpty()) {
                eligibleGroups.firstOrNull { (group, _) -> group.id == preferredGroup }?.let { resolved ->
                    return TrackerMapGroupModeSelection(
                        groupId = resolved.first.id,
                        trackerIds = resolved.second,
                    )
                }
            }

            val preferredId = preferredTrackerId?.trim().orEmpty()
            val matchingPreferred = if (preferredId.isNotEmpty()) {
                eligibleGroups.firstOrNull { (_, ids) -> preferredId in ids }
            } else {
                null
            }
            val resolved = matchingPreferred ?: eligibleGroups
                .sortedWith(compareBy({ it.first.name.lowercase() }, { it.first.id }))
                .first()
            return TrackerMapGroupModeSelection(
                groupId = resolved.first.id,
                trackerIds = resolved.second,
            )
        }

        fun resolveStreamingStatus(
            snapshot: LiveStreamSubscriptionState,
            mapLeaseIds: Set<String> = snapshot.leases[StreamingOwner.MAP]?.trackerIds.orEmpty(),
        ): TrackerMapStreamingStatusUiModel {
            val desiredIds = normalizeStreamingIds(mapLeaseIds)
            if (!snapshot.wantsSubscription && desiredIds.isEmpty()) {
                return TrackerMapStreamingStatusUiModel()
            }

            val activeIds = normalizeStreamingIds(snapshot.activeTargets)
            val activeCount = activeIds.size
            val leaseIntentIds = normalizeStreamingIds(snapshot.mergedTargets)
            val desiredMatched = leaseIntentIds.isNotEmpty() && activeIds == leaseIntentIds

            return when (snapshot.connection) {
                ConnectionPhase.STARTING -> TrackerMapStreamingStatusUiModel(
                    status = if (activeCount > 0 && snapshot.hasConnectedThisProcess) {
                        TrackerMapStreamingStatus.RECONNECTING
                    } else {
                        TrackerMapStreamingStatus.CONNECTING
                    },
                    activeCount = activeCount,
                )
                ConnectionPhase.RECONNECTING -> TrackerMapStreamingStatusUiModel(
                    status = TrackerMapStreamingStatus.RECONNECTING,
                    activeCount = activeCount,
                )
                ConnectionPhase.RUNNING -> TrackerMapStreamingStatusUiModel(
                    status = if (desiredMatched) {
                        TrackerMapStreamingStatus.LIVE
                    } else if (activeCount > 0) {
                        TrackerMapStreamingStatus.RECONNECTING
                    } else {
                        TrackerMapStreamingStatus.CONNECTING
                    },
                    activeCount = activeCount,
                )
                ConnectionPhase.FAILED_TRANSIENT -> TrackerMapStreamingStatusUiModel(
                    status = TrackerMapStreamingStatus.RECONNECTING,
                    activeCount = activeCount,
                    failureReason = snapshot.failureReason,
                )
                ConnectionPhase.FAILED_PERMANENT -> TrackerMapStreamingStatusUiModel(
                    status = TrackerMapStreamingStatus.FAILED,
                    activeCount = activeCount,
                    failureReason = snapshot.failureReason,
                )
                ConnectionPhase.IDLE -> TrackerMapStreamingStatusUiModel()
            }
        }

        private fun eligibleGroups(
            groups: List<Group>,
            hiddenGroupIds: Set<String>,
            hiddenTrackIds: Set<String>,
            hiddenOwnerTrackerIds: Set<String>,
            rosterTrackerIds: Set<String>,
        ): List<Pair<Group, Set<String>>> {
            return groups
                .asSequence()
                .filter { it.is_accepted != false }
                .filter { it.id !in hiddenGroupIds }
                .map { group ->
                    val ids = group.track_ids.orEmpty()
                        .map { it.trim() }
                        .filter {
                            it.isNotEmpty() && it !in hiddenTrackIds && it !in hiddenOwnerTrackerIds &&
                                it in rosterTrackerIds
                        }
                        .toSet()
                    group to ids
                }
                .filter { (_, ids) -> ids.isNotEmpty() }
                .toList()
        }

        private fun normalizeStreamingIds(ids: Set<String>): Set<String> {
            return ids.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()
        }

        private fun setOfNotBlank(value: String?): Set<String> {
            val normalized = value?.trim().orEmpty()
            return normalized.takeIf { it.isNotEmpty() }?.let(::setOf).orEmpty()
        }

        private fun resolveStreamingCommand(
            plan: TrackerMapStreamingPlan,
            state: TrackerMapUiState,
        ): TrackerMapStreamingCommand {
            return resolveStreamingCommand(
                TrackerMapStreamingDecisionInput(
                    mode = plan.mode,
                    remoteSubscriptionIds = plan.remoteSubscriptionIds,
                    displayedTrackerId = plan.displayedTrackerId,
                    displayedTrackerName = plan.displayedTrackerName.ifBlank { state.displayedTrackerName },
                )
            )
        }
    }
}
