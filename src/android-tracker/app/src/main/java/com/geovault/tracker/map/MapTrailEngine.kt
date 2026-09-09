package com.geovault.tracker.map

import com.geovault.common.concurrent.GeoVaultStateStore
import com.geovault.common.logging.CaptureLogThrottle
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.data.CatalogSelectionController
import com.geovault.tracker.Tracker
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.history.TrackerHistoryClearBoundary
import com.geovault.tracker.history.TrackerHistoryDiagnostics
import com.geovault.tracker.history.TrackerHistoryIntent
import com.geovault.tracker.history.TrackerHistoryIntentDispatcher
import com.geovault.tracker.history.TrackerHistoryKey
import com.geovault.tracker.history.TrackerHistoryProvenance
import com.geovault.tracker.history.TrackerHistoryRenderMapper
import com.geovault.tracker.history.TrackerHistoryRenderWindowPolicy
import com.geovault.tracker.history.TrackerHistoryRepository
import com.geovault.tracker.history.TrackerHistorySnapshot
import com.geovault.tracker.history.TrackerHistoryTransactionResult
import com.geovault.tracker.history.publishesSnapshot
import com.geovault.tracker.history.HistoryTrunkIngestor
import com.geovault.tracker.history.TrackerHistoryPoint
import com.geovault.tracker.history.TrackerHistoryPointKey
import com.geovault.tracker.history.TrackerHistorySourceAdapters
import com.geovault.tracker.history.TrackerHistorySourceBatch
import com.geovault.tracker.history.TrackerHistorySourceKind
import com.geovault.tracker.history.TrackerHistoryWindow
import com.geovault.tracker.history.TrackerHistoryWindowResolver
import com.geovault.tracker.policy.RemoteTrackPointAdmissionDiagnostics
import com.geovault.tracker.policy.RemoteTrackPointAdmissionStage
import com.geovault.tracker.policy.StreamingTargetPolicy
import com.geovault.tracker.domain.TrackPoint
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.presentation.HiddenMapItemsPolicy
import com.geovault.tracker.presentation.TrackerMapDisplayMode
import com.geovault.tracker.presentation.TrackerMapFitTrailMode
import com.geovault.tracker.presentation.TrackerMapGroupModeSelection
import com.geovault.tracker.history.TrackerHistorySessionAttribution
import com.geovault.tracker.history.TrackerHistorySessionAttributionContext
import com.geovault.tracker.history.TrackerHistorySessionSegment
import com.geovault.tracker.policy.WireTimestampNormalizer
import com.geovault.tracker.presentation.TrackerMapPointReductionInput
import com.geovault.tracker.presentation.TrackerMapPointReductionResult
import com.geovault.tracker.presentation.TrackerMapPointRoute
import com.geovault.tracker.presentation.TrackerMapPointRouter
import com.geovault.tracker.presentation.TrackerMapPointStartTimestampParser
import com.geovault.tracker.presentation.TrackerMapSessionBuildInput
import com.geovault.tracker.presentation.TrackerMapSessionPointInput
import com.geovault.tracker.presentation.TrackerMapSessionPointResult
import com.geovault.tracker.presentation.TrackerMapSessionSnapshot
import com.geovault.tracker.presentation.TrackerMapStreamSeedInput
import com.geovault.tracker.presentation.TrackerMapStreamingPlan
import com.geovault.tracker.presentation.TrackerMapTrailLoadResult
import com.geovault.tracker.presentation.TrackerMapTrailReloadInput
import com.geovault.tracker.presentation.TrackerMapTrailReloadPlan
import com.geovault.tracker.presentation.TrackerMapTrailReloadReason
import com.geovault.tracker.presentation.TrackerMapTrailSeedInput
import com.geovault.tracker.presentation.TrackerMapTrailSource
import com.geovault.tracker.presentation.TrackerMapUiState
import com.geovault.tracker.presentation.TrackerMapViewModel
import com.geovault.tracker.presentation.TrailReloadGuardInput
import java.util.TreeSet
import kotlin.math.min
import kotlin.math.roundToInt
import com.geovault.tracker.presentation.hasAnyMapLockActive
import com.geovault.tracker.presentation.mergedWith
import com.geovault.tracker.history.TrackerHistoryRefreshCause
import com.geovault.tracker.history.TrackerHistoryRefreshDecision
import com.geovault.tracker.history.TrackerHistoryRefreshInput
import com.geovault.tracker.presentation.TrackerHistoryRefreshReasonMapper
import com.geovault.tracker.positioning.TrackingRuntimeSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class MapTrailEngine {
    private val views = GeoVaultStateStore(TrailView())
    val trail: StateFlow<TrailView> = views.state
    private val liveHeads = LiveHeadStore()

    private var runtime: TrackerMapRuntime? = null
    private var runtimeTrailReloadJob: Job? = null
    private var runtimeTrailReloadPendingReason: TrackerMapTrailReloadReason? = null
    private var nextTrailReloadId: Long = 1L
    private var lastTrailLoadSeed: String? = null
    private var lastStreamTargetsSeed: String? = null
    private var reloadFitArmed = false
    private var reloadFitArmedGeneration: Long = -1L

    fun publish(snapshot: TrackerMapSessionSnapshot, tracks: Map<String, List<QueuedLocation>>) {
        liveHeads.replaceRemotes(snapshot.acceptedRemoteLastPoints)
        views.replace(
            TrailView(
                singleTrail = snapshot.singleTrail,
                tracksByTrackerId = tracks,
                remoteLastPoints = liveHeads.value,
                degradedTrackerIds = views.state.value.degradedTrackerIds,
                snapshot = snapshot,
            )
        )
    }

    fun publish(snapshot: TrackerMapSessionSnapshot) {
        publish(snapshot, snapshot.renderTrailsByTracker)
    }

    fun publish(trails: TrailsFromHistory, remoteLastPoints: Map<String, TrackPoint> = emptyMap()) {
        liveHeads.replaceRemotes(remoteLastPoints)
        views.replace(
            TrailView(
                singleTrail = trails.trail,
                tracksByTrackerId = trails.allQueueTrailsByTracker,
                remoteLastPoints = liveHeads.value,
                degradedTrackerIds = trails.degradedTrackerIds,
                snapshot = views.state.value.snapshot,
            )
        )
    }

    fun removeTrackerGeometry(trackerId: String, clearSingleTrail: Boolean) {
        val id = trackerId.trim()
        if (id.isEmpty()) return
        liveHeads.remove(id)
        views.update { current ->
            current.copy(
                singleTrail = if (clearSingleTrail) emptyList() else current.singleTrail,
                tracksByTrackerId = current.tracksByTrackerId - id,
                remoteLastPoints = liveHeads.value,
            )
        }
    }

    fun resetGeometry(preservedSingle: List<QueuedLocation> = emptyList()) {
        liveHeads.clear()
        views.replace(
            TrailView(
                singleTrail = preservedSingle,
                snapshot = views.state.value.snapshot,
            )
        )
    }

    fun replace(trails: TrailView) {
        liveHeads.replaceRemotes(trails.remoteLastPoints)
        views.replace(
            trails.copy(
                remoteLastPoints = liveHeads.value,
                snapshot = trails.snapshot ?: views.state.value.snapshot,
            )
        )
    }

    fun replaceRemoteLastPoints(remoteLastPoints: Map<String, TrackPoint>) {
        liveHeads.replaceRemotes(remoteLastPoints)
        views.update { it.copy(remoteLastPoints = liveHeads.value) }
    }

    fun clearRemoteLastPoints() {
        liveHeads.clearRemotes()
        views.update { it.copy(remoteLastPoints = liveHeads.value) }
    }

    internal fun start(rt: TrackerMapRuntime) {
        runtime = rt
    }

    internal fun invalidateLoadedSeed() {
        lastTrailLoadSeed = null
    }

    internal fun publishRuntimeHead(runtime: TrackingRuntimeSnapshot, plan: TrackerMapStreamingPlan) {
        syncRuntimeHead(runtime = runtime, plan = plan, liveHeads = liveHeads)
        views.update { it.copy(remoteLastPoints = liveHeads.value) }
    }

    internal fun applyHistoryTrailsToState(
        state: TrackerMapUiState,
        plan: TrackerMapStreamingPlan,
    ): TrackerMapUiState {
        val rt = runtime ?: return state
        val trackers = rt.catalog().trackers
        val visibleIds = visibleTrackerIdsForSessionPlan(state, plan)
        publishRuntimeHead(rt.recording(), plan)
        val unpublished = unpublishedOverlaysByTracker(
            ingestor = rt.dependencies.historyTrunkIngestor,
            trackerIds = historyTrackerIdsForRender(state, plan, visibleIds),
            trackers = trackers,
        )
        val previous = views.state.value
        val trails = trailsFromSnapshots(
            state = state,
            plan = plan,
            snapshots = rt.dependencies.historyRepository.snapshots.value,
            trackers = trackers,
            trailPointLimit = TrackerMapViewModel.TRAIL_POINT_LIMIT,
            visibleTrackerIds = visibleIds,
            unpublishedOverlaysByTracker = unpublished,
            previousSingleTrail = previous.singleTrail,
            previousMultiTrails = previous.tracksByTrackerId,
            previousRemoteLastPoints = liveHeads.value,
        )
        seedRemoteHeadsFromDrawnTrails(trails.trail, trails.allQueueTrailsByTracker)
        publish(trails, remoteLastPoints = liveHeads.value)
        return state
    }

    private fun seedRemoteHeadsFromDrawnTrails(
        singleTrail: List<QueuedLocation>,
        multi: Map<String, List<QueuedLocation>>,
    ) {
        val tails = LinkedHashMap<String, QueuedLocation>()
        singleTrail.lastOrNull()?.let { loc ->
            loc.trackerId.trim().takeIf { it.isNotEmpty() }?.let { tails[it] = loc }
        }
        multi.forEach { (id, trail) ->
            val key = id.trim()
            val loc = trail.lastOrNull() ?: return@forEach
            if (key.isNotEmpty()) tails[key] = loc
        }
        tails.forEach { (id, loc) ->
            if (loc.time <= 0L) return@forEach
            liveHeads.upsert(
                TrackPoint(
                    trackerId = id,
                    timeMs = loc.time,
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    provenance = TrackPointSource.REMOTE_STREAM,
                    accuracyMeters = loc.accuracy,
                )
            )
        }
    }

    internal fun requestRuntimeTrailReload(reason: TrackerMapTrailReloadReason) {
        val rt = runtime ?: return
        if (runtimeTrailReloadJob?.isActive == true) {
            runtimeTrailReloadPendingReason = runtimeTrailReloadPendingReason.mergedWith(reason)
            return
        }
        runtimeTrailReloadJob = rt.ports.viewModelScope.launch {
            var nextReason: TrackerMapTrailReloadReason? = reason
            while (nextReason != null) {
                val current = nextReason
                runtimeTrailReloadPendingReason = null
                reloadTrailFromSnapshots(current)
                nextReason = runtimeTrailReloadPendingReason
            }
        }
    }

    internal suspend fun requestAndAwaitRuntimeTrailReload(reason: TrackerMapTrailReloadReason) {
        requestRuntimeTrailReload(reason)
        runtimeTrailReloadJob?.join()
    }

    internal fun requestTrailReloadForStreamingScopeChange(
        state: TrackerMapUiState,
        plan: TrackerMapStreamingPlan,
        groupSelection: TrackerMapGroupModeSelection,
        previousStreamTargetIds: Set<String>,
    ) {
        val rt = runtime ?: return
        val seed = streamSeed(
            TrackerMapStreamSeedInput(
                mode = plan.mode,
                runtimeRunning = rt.recording().localRecordingActive,
                selectedTrackerId = plan.selectedTrackerId,
                displayedTrackerId = plan.displayedTrackerId,
                rosterTrackerIds = plan.visibleRosterTrackerIds,
                groupSelection = groupSelection
            )
        )
        val seedChanged = seed != lastStreamTargetsSeed
        lastStreamTargetsSeed = seed
        val nextStreamTargetIds = plan.remoteSubscriptionIds
        val shouldLoadHistoryForStreamingStart = seedChanged &&
            nextStreamTargetIds.isNotEmpty() &&
            nextStreamTargetIds != previousStreamTargetIds
        if (shouldLoadHistoryForStreamingStart) {
            requestRuntimeTrailReload(TrackerMapTrailReloadReason.StreamingStart)
        }
    }

    internal fun reduce(point: TrackPoint) {
        val rt = runtime ?: return
        val precheckPlan = rt.sessionEngine.resolvePlan(rt.stateHub.uiStateMutable.value)
        if (!TrackerMapPointRouter.route(point, precheckPlan).accepted) {
            logReduceResult(point, accepted = false, updated = false)
            return
        }
        var shouldUpdate = false
        var rejectedByContextSwitch = false
        rt.stateHub.uiStateMutable.update { latest ->
            shouldUpdate = false
            rejectedByContextSwitch = false
            val plan = rt.sessionEngine.resolvePlan(latest)
            val route = TrackerMapPointRouter.route(point, plan)
            if (!route.accepted) {
                rejectedByContextSwitch = true
                return@update latest
            }
            if (route.updateRemoteLastPoint) {
                liveHeads.upsert(point.copy(trackerId = route.normalizedTrackerId))
                views.update { current ->
                    current.copy(remoteLastPoints = liveHeads.value)
                }
                shouldUpdate = true
            }

            if (route.appendSingleTrail || route.appendMultiTrail) {
                val overlayCommitted = dispatchLiveOverlay(
                    point = point,
                    trackers = rt.catalog().trackers,
                    dispatcher = rt.dependencies.historyIntentDispatcher,
                    activeSessionStartMs = rt.activeSessionStartMsForTracker(point.trackerId),
                )
                if (overlayCommitted) {
                    shouldUpdate = true
                }
            }

            if (!shouldUpdate) return@update latest
            val withTrails = applyHistoryTrailsToState(latest, plan)
            rt.sessionEngine.stateWithRefreshedSelectionCard(withTrails, point.trackerId)
        }
        if (rejectedByContextSwitch) {
            logReduceResult(point, accepted = false, updated = false, contextSwitch = true)
            return
        }
        if (shouldUpdate) {
            val nextState = rt.stateHub.uiStateMutable.value
            logReduceResult(point, accepted = true, updated = true, nextState = nextState)
        }
    }

    internal suspend fun seedInitialTrailFromLocalQueue() {
        val rt = runtime ?: return
        val context = rt.ports.application
        val selectedId = CatalogSelectionController.persistedTrackerId(context)
        if (selectedId.isEmpty()) return
        val queueTrail = loadQueueTrail(selectedId)
        if (queueTrail.isEmpty()) return
        rt.withTrailCommit {
            commitQueueOverlays(
                queueOverlaysByTracker = mapOf(selectedId to queueTrail),
                trackers = rt.catalog().trackers,
                dispatcher = rt.dependencies.historyIntentDispatcher,
                activeSessionStartMsFor = rt::activeSessionStartMsForTracker,
            )
            rt.stateHub.uiStateMutable.update { latest ->
                val displayedNow = latest.displayedTrackerId.trim()
                if (displayedNow.isNotEmpty() && displayedNow != selectedId) return@update latest
                val plan = rt.projectSession(latest)
                applyHistoryTrailsToState(latest, plan)
                if (!views.state.value.hasTrailPoints) {
                    return@update latest
                }
                val trailsState = latest
                val displayedId = if (latest.displayedTrackerId.isBlank()) {
                    selectedId
                } else {
                    latest.displayedTrackerId
                }
                val displayedName = if (latest.displayedTrackerName.isBlank()) {
                    CatalogSelectionController.persistedTrackerName(context)
                } else {
                    latest.displayedTrackerName
                }
                trailsState.copy(
                    displayedTrackerId = displayedId,
                    displayedTrackerName = displayedName,
                )
            }
        }
        publishTrailView()
    }

    internal suspend fun loadQueueTrail(trackerId: String): List<QueuedLocation> {
        val rt = runtime ?: return emptyList()
        val normalizedTrackerId = trackerId.trim()
        if (normalizedTrackerId.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            rt.dependencies.dao.getRecentChronologicalForTracker(normalizedTrackerId, TrackerMapViewModel.QUEUE_TRAIL_FETCH_LIMIT)
        }
    }

    private suspend fun reloadTrailFromSnapshots(reason: TrackerMapTrailReloadReason) {
        val planResult = planAndGuardReload(reason) ?: return
        val mergeCommitted = commitSnapshotReload(planResult)
        applyCameraFitAfterReload(reason, mergeCommitted)
    }

    private suspend fun planAndGuardReload(reason: TrackerMapTrailReloadReason): ReloadPlanContext? {
        val rt = runtime ?: return null
        val state = rt.stateHub.uiStateMutable.value
        val published = views.state.value
        val reloadId = nextTrailReloadId++
        GeoVaultCaptureLog.i(
            TrackerMapViewModel.TAG,
            "map_update vm_reload_start reloadId=$reloadId reason=$reason mode=${state.mode} displayed=${state.displayedTrackerId.trim()} " +
                "selected=${rt.catalogSelectedTrackerId().trim()} localActive=${rt.recording().localRecordingActive} " +
                "trail=${published.singleTrail.trailSummary()} multi=${published.tracksByTrackerId.mapSizes()}"
        )
        val groupSelection = rt.resolveGroupModeSelection(state)
        val rosterTrackerIds = rt.visibleMapRosterTrackerIds()
        val sessionPlan = rt.projectSession(
            state = state,
            groupSelection = groupSelection,
            visibleRosterTrackerIds = rosterTrackerIds,
        )
        val activeTrackerId = sessionPlan.displayedTrackerId
        val guardInput = TrailReloadGuardInput(
            mode = state.mode,
            trailSize = published.singleTrail.size,
            runtimeRunning = rt.recording().localRecordingActive,
            displayedTrackerId = activeTrackerId,
            trailReloadPlan = sessionPlan.trailReloadPlan,
        )
        if (!shouldProceedReload(guardInput)) {
            GeoVaultCaptureLog.d(
                TrackerMapViewModel.TAG,
                "map_update vm_reload_guard_skip reason=$reason mode=${state.mode} trailSize=${published.singleTrail.size} " +
                    "runtimeRunning=${rt.recording().localRecordingActive} source=${sessionPlan.trailReloadPlan.source}"
            )
            return null
        }
        if (reason.allowServerHistoryFetch) {
            val nowMs = System.currentTimeMillis()
            val refreshCause = TrackerHistoryRefreshReasonMapper.toRefreshCause(reason)
            val staleRosterIds = rosterTrackerIdsForTrunkStaleCheck(
                state = state,
                sessionPlan = sessionPlan,
                groupSelection = groupSelection,
                visibleRosterTrackerIds = rosterTrackerIds,
            )
            val staleTrackerId = staleRosterIds.singleOrNull()
                ?: sessionPlan.displayedTrackerId.trim().ifBlank { rt.catalogSelectedTrackerId().trim() }
            val lastTrunkMs = staleTrackerId.takeIf { it.isNotEmpty() }
                ?.let { rt.dependencies.historyRepository.lastTrunkFetchedAtMs(it) }
            val refreshDecision = if (
                staleRosterIds.size > 1 &&
                (refreshCause == TrackerHistoryRefreshCause.Resume ||
                    refreshCause == TrackerHistoryRefreshCause.PeriodicRecording)
            ) {
                val anyStale = staleRosterIds.any { trackerId ->
                    val last = rt.dependencies.historyRepository.lastTrunkFetchedAtMs(trackerId)
                    last == null || nowMs - last >= TrackerHistoryRefreshInput.DEFAULT_STALE_AFTER_MS
                }
                TrackerHistoryRefreshDecision(
                    shouldRefresh = anyStale,
                    reason = if (anyStale) "stale_trunk" else "fresh_trunk",
                )
            } else {
                resolveTrunkRefresh(
                    TrackerHistoryRefreshInput(
                        cause = refreshCause,
                        nowMs = nowMs,
                        lastTrunkFetchedAtMs = lastTrunkMs,
                        trackerIdForStaleCheck = staleTrackerId.takeIf { it.isNotEmpty() },
                        isRecording = rt.recording().localRecordingActive,
                    ),
                )
            }
            TrackerHistoryDiagnostics.logRefreshDecision(
                cause = refreshCause,
                shouldRefresh = refreshDecision.shouldRefresh,
                policyReason = refreshDecision.reason,
                lastTrunkFetchedAtMs = lastTrunkMs,
                nowMs = nowMs,
            )
            if (!refreshDecision.shouldRefresh) {
                return null
            }
        }
        if (!reason.allowsSource(sessionPlan.trailReloadPlan.source)) {
            rt.withTrailCommit {
                reconcileLocalQueueOverlayForSkippedReload(
                    reason = reason,
                    plan = sessionPlan.trailReloadPlan,
                )
            }
            val skipSignature = "reason=$reason|source=${sessionPlan.trailReloadPlan.source}"
            if (CaptureLogThrottle.shouldLogOnChange("vm_reload_skip_source", skipSignature)) {
                GeoVaultCaptureLog.d(
                    TrackerMapViewModel.TAG,
                    "map_update vm_reload_skip_source reloadId=$reloadId reason=$reason source=${sessionPlan.trailReloadPlan.source} " +
                        "displayed=${sessionPlan.displayedTrackerId} trail=${published.singleTrail.size}"
                )
            }
            return null
        }
        if (reason.allowServerHistoryFetch) {
            armReloadFit(reason, rt.renderEngine.cameraGeneration)
        }
        val seed = trailSeed(
            TrackerMapTrailSeedInput(
                mode = state.mode,
                runtimeRunning = rt.recording().localRecordingActive,
                activeTrackerId = sessionPlan.displayedTrackerId,
                rosterTrackerIds = rosterTrackerIds,
                groupSelection = groupSelection,
                renderMetadataSignature = state.renderMetadataSignature,
            )
        )
        if (!reason.allowServerHistoryFetch && lastTrailLoadSeed == seed) {
            GeoVaultCaptureLog.v(TrackerMapViewModel.TAG, "map_update vm_reload_seed_skip reason=$reason seed=$seed")
            return null
        }
        lastTrailLoadSeed = seed
        val planSourceState = rt.stateHub.uiStateMutable.value
        val plan = rt.projectSession(
            state = planSourceState,
            groupSelection = groupSelection,
            visibleRosterTrackerIds = rosterTrackerIds,
        ).trailReloadPlan
        GeoVaultCaptureLog.i(
            TrackerMapViewModel.TAG,
            "map_update vm_reload_plan reloadId=$reloadId reason=$reason source=${plan.source} active=${plan.activeTrackerId} " +
                "single=${plan.singleTrackerId} trackers=${plan.trackerIds.sorted()} overlay=${plan.overlayTrackerId} seed=$seed"
        )
        return ReloadPlanContext(
            reloadId = reloadId,
            reason = reason,
            seed = seed,
            groupSelection = groupSelection,
            rosterTrackerIds = rosterTrackerIds,
            plan = plan,
        )
    }

    private suspend fun commitSnapshotReload(planResult: ReloadPlanContext): MergedTrailResult? {
        val rt = runtime ?: return null
        val (reloadId, reason, seed, groupSelection, rosterTrackerIds, plan) = planResult
        var mergeCommitted: MergedTrailResult? = null
        rt.withTrailCommit {
            val overlayTrackerId = plan.overlayTrackerId?.trim().orEmpty()
            if (overlayTrackerId.isNotEmpty() && rt.recording().localRecordingActive) {
                val queueOverlay = loadQueueTrail(overlayTrackerId)
                if (queueOverlay.isNotEmpty()) {
                    commitQueueOverlays(
                        queueOverlaysByTracker = mapOf(overlayTrackerId to queueOverlay),
                        trackers = rt.catalog().trackers,
                        dispatcher = rt.dependencies.historyIntentDispatcher,
                        activeSessionStartMsFor = rt::activeSessionStartMsForTracker,
                    )
                }
            }
            rt.stateHub.uiStateMutable.update { latest ->
                if (skipReloadCommitForStaleSeed(plannedSeed = seed, currentSeed = trailSeedForState(latest))) {
                    return@update latest
                }
                val sessionPlan = rt.projectSession(
                    state = latest,
                    groupSelection = groupSelection,
                    visibleRosterTrackerIds = rosterTrackerIds,
                )
                applyHistoryTrailsToState(latest, sessionPlan)
                val published = views.state.value
                mergeCommitted = MergedTrailResult(
                    trail = published.singleTrail,
                    multiTrails = published.tracksByTrackerId,
                )
                GeoVaultCaptureLog.i(
                    TrackerMapViewModel.TAG,
                    "map_update vm_reload_commit reloadId=$reloadId reason=$reason source=${plan.source} " +
                        "trail=${published.singleTrail.trailSummary()} multi=${published.tracksByTrackerId.mapSizes()}",
                )
                latest.copy(
                    currentGroupId = if (latest.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
                        sessionPlan.resolvedGroupId
                    } else {
                        latest.currentGroupId
                    },
                    groupModeOptions = if (latest.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
                        rt.resolveGroupModeOptions()
                    } else {
                        emptyList()
                    },
                )
            }
        }
        if (mergeCommitted == null) {
            abandonStaleReload(reason)
        } else {
            publishTrailView()
        }
        return mergeCommitted
    }

    private fun applyCameraFitAfterReload(reason: TrackerMapTrailReloadReason, mergeCommitted: MergedTrailResult?) {
        val rt = runtime ?: return
        val finalMerge = mergeCommitted ?: run {
            disarmReloadFit(reason)
            return
        }
        val hasData = finalMerge.trail.isNotEmpty() || finalMerge.multiTrails.isNotEmpty()
        val anyLockActive = rt.stateHub.uiStateMutable.value.hasAnyMapLockActive()
        if (
            consumeReloadFitIfLanded(
                reason = reason,
                hasData = hasData,
                anyLockActive = anyLockActive,
                currentGeneration = rt.renderEngine.cameraGeneration,
            )
        ) {
            rt.sessionEngine.requestFitTrail(TrackerMapFitTrailMode.Instant)
        }
    }

    private suspend fun reconcileLocalQueueOverlayForSkippedReload(
        reason: TrackerMapTrailReloadReason,
        plan: TrackerMapTrailReloadPlan,
    ) {
        val rt = runtime ?: return
        val overlayTrackerId = plan.overlayTrackerId?.trim().orEmpty()
        if (overlayTrackerId.isEmpty()) return
        if (!rt.recording().localRecordingActive) return

        val currentTrails = views.state.value
        val loaded = loadLocalOverlay(
            plan = plan,
            currentSingleTrail = currentTrails.singleTrail,
            currentMultiTrails = currentTrails.tracksByTrackerId,
            loadQueue = { trackerId -> loadQueueTrail(trackerId) },
        )
        val queueOverlay = loaded.queueOverlaysByTracker[overlayTrackerId].orEmpty()
        if (queueOverlay.isEmpty()) {
            val skipSignature = "reason=$reason|source=${plan.source}|overlay=$overlayTrackerId"
            if (CaptureLogThrottle.shouldLogOnChange("vm_local_overlay_skip_empty", skipSignature)) {
                GeoVaultCaptureLog.d(
                    TrackerMapViewModel.TAG,
                    "map_update vm_local_overlay_skip_empty reason=$reason source=${plan.source} overlay=$overlayTrackerId"
                )
            }
            return
        }

        commitQueueOverlays(
            queueOverlaysByTracker = loaded.queueOverlaysByTracker,
            trackers = rt.catalog().trackers,
            dispatcher = rt.dependencies.historyIntentDispatcher,
            activeSessionStartMsFor = rt::activeSessionStartMsForTracker,
        )
        rt.stateHub.uiStateMutable.update { latest ->
            val latestPlan = rt.projectSession(
                state = latest,
                groupSelection = rt.resolveGroupModeSelection(latest),
                visibleRosterTrackerIds = rt.visibleMapRosterTrackerIds(),
            )
            val latestOverlayTrackerId = latestPlan.trailReloadPlan.overlayTrackerId?.trim().orEmpty()
            if (latestOverlayTrackerId != overlayTrackerId || latestPlan.trailReloadPlan.source != plan.source) {
                return@update latest
            }
            applyHistoryTrailsToState(latest, latestPlan)
            val published = views.state.value
            GeoVaultCaptureLog.i(
                TrackerMapViewModel.TAG,
                "map_update vm_local_overlay_commit reason=$reason source=${latestPlan.trailReloadPlan.source} overlay=$overlayTrackerId " +
                    "queue=${queueOverlay.trailSummary()} trail=${published.singleTrail.trailSummary()} " +
                    "multi=${published.tracksByTrackerId.mapSizes()}",
            )
            latest
        }
        publishTrailView()
    }

    private fun publishTrailView() {
        val rt = runtime ?: return
        publish(rt.renderEngine.buildCurrentSessionSnapshot())
    }

    private fun visibleTrackerIdsForSessionPlan(
        state: TrackerMapUiState,
        plan: TrackerMapStreamingPlan,
    ): Set<String>? {
        val rt = runtime ?: return null
        return when (state.mode) {
            TrackerMapDisplayMode.GROUP_PLACEHOLDER -> HiddenMapItemsPolicy
                .visibleTrackerIdsForMap(
                    rosterTrackerIds = plan.groupTrackerIds,
                    mapVisibility = rt.catalog().mapVisibility,
                    trackers = rt.catalog().trackers,
                )
            TrackerMapDisplayMode.ALL_QUEUE -> plan.visibleRosterTrackerIds
            TrackerMapDisplayMode.SINGLE_SESSION -> null
        }
    }

    private fun rosterTrackerIdsForTrunkStaleCheck(
        state: TrackerMapUiState,
        sessionPlan: TrackerMapStreamingPlan,
        groupSelection: TrackerMapGroupModeSelection,
        visibleRosterTrackerIds: Set<String>,
    ): Set<String> {
        return when (state.mode) {
            TrackerMapDisplayMode.GROUP_PLACEHOLDER -> {
                groupSelection.trackerIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            }
            TrackerMapDisplayMode.ALL_QUEUE -> {
                visibleRosterTrackerIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            }
            TrackerMapDisplayMode.SINGLE_SESSION -> {
                val id = sessionPlan.displayedTrackerId.trim().ifBlank { sessionPlan.selectedTrackerId.trim() }
                if (id.isEmpty()) emptySet() else setOf(id)
            }
        }
    }

    private fun trailSeedForState(state: TrackerMapUiState): String {
        val rt = runtime ?: return ""
        val groupSelection = rt.resolveGroupModeSelection(state)
        val rosterIds = rt.visibleMapRosterTrackerIds()
        val plan = rt.projectSession(
            state = state,
            groupSelection = groupSelection,
            visibleRosterTrackerIds = rosterIds,
        )
        return trailSeed(
            TrackerMapTrailSeedInput(
                mode = state.mode,
                runtimeRunning = rt.recording().localRecordingActive,
                activeTrackerId = plan.displayedTrackerId,
                rosterTrackerIds = rosterIds,
                groupSelection = groupSelection,
                renderMetadataSignature = state.renderMetadataSignature,
            )
        )
    }

    private fun TrackerMapTrailReloadReason.allowsSource(source: TrackerMapTrailSource): Boolean {
        return when {
            allowServerHistoryFetch -> true
            allowMultiServerHistoryFetch && source == TrackerMapTrailSource.MULTI_SERVER -> true
            source == TrackerMapTrailSource.SINGLE_QUEUE -> true
            else -> false
        }
    }

    private fun logReduceResult(
        point: TrackPoint,
        accepted: Boolean,
        updated: Boolean,
        contextSwitch: Boolean = false,
        nextState: TrackerMapUiState? = null,
    ) {
        val throttleKey = if (accepted) "vm_point_reduce_accept" else "vm_point_reduce_reject"
        val signature = "source=${point.provenance}|track=${point.trackerId.trim()}|accepted=$accepted|ctxSwitch=$contextSwitch"
        if (!CaptureLogThrottle.shouldLogOnChange(throttleKey, signature)) return
        val detail = if (nextState != null) {
            val published = views.state.value
            " singleAfter=${published.singleTrail.trailSummary()} multiAfter=${published.tracksByTrackerId.mapSizes()}"
        } else if (contextSwitch) {
            " reason=context_switch_invalidated_plan"
        } else {
            ""
        }
        GeoVaultCaptureLog.d(
            TrackerMapViewModel.TAG,
            "map_update vm_point_reduce_result source=${point.provenance} track=${point.trackerId.trim()} " +
                "accepted=$accepted update=$updated$detail",
        )
    }

    private data class ReloadPlanContext(
        val reloadId: Long,
        val reason: TrackerMapTrailReloadReason,
        val seed: String,
        val groupSelection: TrackerMapGroupModeSelection,
        val rosterTrackerIds: Set<String>,
        val plan: TrackerMapTrailReloadPlan,
    )

    private data class MergedTrailResult(
        val trail: List<QueuedLocation>,
        val multiTrails: Map<String, List<QueuedLocation>>,
    )

    data class TrailsFromHistory(
        val trail: List<QueuedLocation>,
        val allQueueTrailsByTracker: Map<String, List<QueuedLocation>>,
        val degradedTrackerIds: Set<String> = emptySet(),
    )

    companion object {
        const val PROVENANCE_LOCAL_GPS = "local_gps"
        const val PROVENANCE_LOCAL_GPS_RUNTIME = "local_gps_runtime"
        const val PROVENANCE_REMOTE_STREAM = "remote_stream"
        const val PROVENANCE_SERVER_GEOMETRY = "server_geometry"

        fun isLiveOverlay(point: QueuedLocation): Boolean {
            return when (point.prov?.trim()) {
                PROVENANCE_LOCAL_GPS,
                PROVENANCE_LOCAL_GPS_RUNTIME,
                PROVENANCE_REMOTE_STREAM -> true
                else -> false
            }
        }

        fun isServerHistory(point: QueuedLocation): Boolean {
            return point.prov?.trim() == PROVENANCE_SERVER_GEOMETRY
        }

        fun normalizeTimestampToMs(value: Any?): Long? {
            return WireTimestampNormalizer.normalizeToMilliseconds(value)
        }

        fun streamSeed(input: TrackerMapStreamSeedInput): String {
            val trackerRosterSignature = normalizedIdsSignature(input.rosterTrackerIds)
            val groupModeSignature = groupSelectionSignature(input.groupSelection)
            return "${input.mode}|${input.runtimeRunning}|${input.selectedTrackerId}|${input.displayedTrackerId}|$trackerRosterSignature|$groupModeSignature"
        }

        fun trailSeed(input: TrackerMapTrailSeedInput): String {
            val rosterSignature = normalizedIdsSignature(input.rosterTrackerIds)
            val groupModeSignature = groupSelectionSignature(input.groupSelection)
            return "${input.mode}|${input.runtimeRunning}|${input.activeTrackerId}|$rosterSignature|$groupModeSignature|${input.renderMetadataSignature}"
        }

        fun skipReloadCommitForStaleSeed(plannedSeed: String, currentSeed: String): Boolean {
            return currentSeed != plannedSeed
        }

        fun resolveReloadPlan(input: TrackerMapTrailReloadInput): TrackerMapTrailReloadPlan {
            val active = input.activeTrackerId.trim()
            val selected = input.selectedTrackerId.trim()
            val locallyRecorded = input.locallyRecordedTrackerId.trim().ifBlank {
                selected.takeIf { input.runtimeRunning }.orEmpty()
            }
            val rosterIds = input.rosterTrackerIds
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
            val groupIds = input.groupSelection.trackerIds
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
            if (input.mode == TrackerMapDisplayMode.SINGLE_SESSION && active.isNotEmpty()) {
                return TrackerMapTrailReloadPlan(
                    source = TrackerMapTrailSource.SINGLE_SERVER,
                    singleTrackerId = active,
                    overlayTrackerId = locallyRecorded.takeIf { input.runtimeRunning && active == locallyRecorded },
                    activeTrackerId = active,
                )
            }
            if (input.mode == TrackerMapDisplayMode.ALL_QUEUE) {
                return TrackerMapTrailReloadPlan(
                    source = TrackerMapTrailSource.MULTI_SERVER,
                    trackerIds = StreamingTargetPolicy.normalizeTrackerIds(rosterIds),
                    overlayTrackerId = locallyRecorded.takeIf { input.runtimeRunning && it.isNotEmpty() },
                    activeTrackerId = active,
                )
            }
            if (input.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
                return TrackerMapTrailReloadPlan(
                    source = TrackerMapTrailSource.MULTI_SERVER,
                    trackerIds = StreamingTargetPolicy.normalizeTrackerIds(groupIds),
                    overlayTrackerId = locallyRecorded.takeIf {
                        input.runtimeRunning && it.isNotEmpty() && it in groupIds
                    },
                    activeTrackerId = active,
                    resolvedGroupId = input.groupSelection.groupId.orEmpty(),
                )
            }
            return TrackerMapTrailReloadPlan(
                source = TrackerMapTrailSource.SINGLE_QUEUE,
                activeTrackerId = active,
            )
        }

        fun reduceUiPoint(input: TrackerMapPointReductionInput): TrackerMapPointReductionResult {
            val state = input.state
            val point = input.point
            val route = TrackerMapPointRouter.route(point, input.sessionPlan)
            if (point.provenance == TrackPointSource.REMOTE_STREAM) {
                if (route.accepted) {
                    RemoteTrackPointAdmissionDiagnostics.recordAccepted(
                        RemoteTrackPointAdmissionStage.PUBLISH, route.normalizedTrackerId
                    )
                } else {
                    RemoteTrackPointAdmissionDiagnostics.recordRejected(
                        RemoteTrackPointAdmissionStage.VISIBILITY_ROUTING, "not_in_display_scope", point.trackerId
                    )
                }
            }
            if (!route.accepted) {
                return TrackerMapPointReductionResult(
                    acceptedBySourcePolicy = false,
                    shouldUpdateUiState = false,
                    nextState = state,
                    nextTrails = input.trails,
                )
            }
            return when (point.provenance) {
                TrackPointSource.REMOTE_STREAM -> reduceRemoteUiPoint(input, route)
                TrackPointSource.LOCAL_GPS -> reduceLocalUiPoint(input, route)
            }
        }

        fun reducePoint(input: TrackerMapSessionPointInput): TrackerMapSessionPointResult {
            val reduction = reduceUiPoint(
                TrackerMapPointReductionInput(
                    state = input.snapshot.uiState,
                    trails = TrailView(
                        singleTrail = input.snapshot.singleTrail,
                        tracksByTrackerId = input.snapshot.renderTrailsByTracker,
                        remoteLastPoints = input.snapshot.acceptedRemoteLastPoints,
                        snapshot = input.snapshot,
                    ),
                    point = input.point,
                    trailPointLimit = input.trailPointLimit,
                    sessionPlan = input.snapshot.plan,
                )
            )
            if (!reduction.shouldUpdateUiState) {
                return TrackerMapSessionPointResult(
                    acceptedBySourcePolicy = reduction.acceptedBySourcePolicy,
                    shouldUpdate = false,
                    nextSnapshot = input.snapshot,
                )
            }
            val nextSnapshot = MapRenderMath.buildSession(
                TrackerMapSessionBuildInput(
                    state = reduction.nextState,
                    plan = input.snapshot.plan.copy(
                        acceptedRemoteTrackerIds = input.snapshot.plan.acceptedRemoteTrackerIds,
                    ),
                    singleTrail = reduction.nextTrails.singleTrail,
                    localRuntimeOverlayTrails = reduction.nextTrails.tracksByTrackerId,
                    remoteLastPoints = reduction.nextTrails.remoteLastPoints,
                    visibleTrackerIds = input.visibleTrackerIds,
                    nowMs = input.nowMs,
                )
            )
            return TrackerMapSessionPointResult(
                acceptedBySourcePolicy = reduction.acceptedBySourcePolicy,
                shouldUpdate = true,
                nextSnapshot = nextSnapshot,
            )
        }

        private fun reduceRemoteUiPoint(
            input: TrackerMapPointReductionInput,
            route: TrackerMapPointRoute,
        ): TrackerMapPointReductionResult {
            val state = input.state
            val trails = input.trails
            val point = input.point
            val remoteTrackerId = route.normalizedTrackerId
            val nextRemoteLastPoints = if (route.updateRemoteLastPoint) trails.remoteLastPoints.toMutableMap().apply {
                this[remoteTrackerId] = point.copy(trackerId = remoteTrackerId)
            } else {
                trails.remoteLastPoints
            }
            val nextTrail = if (route.appendSingleTrail) {
                appendRemotePoint(trails.singleTrail, point.copy(trackerId = remoteTrackerId), input.trailPointLimit)
            } else {
                trails.singleTrail
            }
            val nextAllQueueTrails = if (route.appendMultiTrail) {
                val updated = trails.tracksByTrackerId.toMutableMap()
                val base = updated[remoteTrackerId].orEmpty()
                updated[remoteTrackerId] = appendRemotePoint(base, point.copy(trackerId = remoteTrackerId), input.trailPointLimit)
                updated
            } else {
                trails.tracksByTrackerId
            }
            return TrackerMapPointReductionResult(
                acceptedBySourcePolicy = true,
                shouldUpdateUiState = true,
                nextState = state,
                nextTrails = trails.copy(
                    remoteLastPoints = nextRemoteLastPoints,
                    singleTrail = nextTrail,
                    tracksByTrackerId = nextAllQueueTrails,
                ),
            )
        }

        private fun reduceLocalUiPoint(
            input: TrackerMapPointReductionInput,
            route: TrackerMapPointRoute,
        ): TrackerMapPointReductionResult {
            val state = input.state
            val trails = input.trails
            val point = input.point
            val overlayTrackerId = route.normalizedTrackerId
            if (overlayTrackerId.isBlank()) {
                return TrackerMapPointReductionResult(
                    acceptedBySourcePolicy = true,
                    shouldUpdateUiState = false,
                    nextState = state,
                    nextTrails = trails,
                )
            }
            val parsedStart = TrackerMapPointStartTimestampParser.parse(point.propsJson)
            val resolvedStart = parsedStart
                ?: state.runtime.sessionStartTimeMs.takeIf { it > 0L }
            val localOverlayPoint = QueuedLocation(
                id = 0L,
                trackerId = overlayTrackerId,
                time = point.timeMs,
                latitude = point.latitude,
                longitude = point.longitude,
                altitude = null,
                speed = null,
                bearing = null,
                accuracy = point.accuracyMeters,
                sat = null,
                prov = PROVENANCE_LOCAL_GPS,
                dist = null,
                startTimestampMs = resolvedStart,
            )
            val nextTrail = if (route.appendSingleTrail) {
                appendQueuedPoint(trails.singleTrail, localOverlayPoint, input.trailPointLimit)
            } else {
                trails.singleTrail
            }
            val nextAllQueueTrails = if (route.appendMultiTrail) {
                val updated = trails.tracksByTrackerId.toMutableMap()
                val base = updated[overlayTrackerId].orEmpty()
                updated[overlayTrackerId] = appendQueuedPoint(base, localOverlayPoint, input.trailPointLimit)
                updated
            } else {
                trails.tracksByTrackerId
            }
            if (nextTrail === trails.singleTrail && nextAllQueueTrails === trails.tracksByTrackerId) {
                return TrackerMapPointReductionResult(
                    acceptedBySourcePolicy = true,
                    shouldUpdateUiState = false,
                    nextState = state,
                    nextTrails = trails,
                )
            }
            return TrackerMapPointReductionResult(
                acceptedBySourcePolicy = true,
                shouldUpdateUiState = true,
                nextState = state,
                nextTrails = trails.copy(
                    singleTrail = nextTrail,
                    tracksByTrackerId = nextAllQueueTrails,
                ),
            )
        }

        private fun appendQueuedPoint(
            currentTrail: List<QueuedLocation>,
            point: QueuedLocation,
            trailPointLimit: Int,
        ): List<QueuedLocation> {
            val last = currentTrail.lastOrNull()
            if (last != null && !isDifferentSession(last.startTimestampMs, point.startTimestampMs)) {
                val duplicate = last.time == point.time &&
                    last.latitude == point.latitude &&
                    last.longitude == point.longitude
                if (duplicate || point.time < last.time) {
                    return currentTrail
                }
            }
            return fitToCount(currentTrail + point, trailPointLimit)
        }

        private fun appendRemotePoint(
            currentTrail: List<QueuedLocation>,
            point: TrackPoint,
            trailPointLimit: Int,
        ): List<QueuedLocation> {
            val normalizedTime = normalizeTimestampToMs(point.timeMs) ?: point.timeMs
            val pointStart = TrackerMapPointStartTimestampParser.parse(point.propsJson)
            val last = currentTrail.lastOrNull()
            if (last != null && !isDifferentSession(last.startTimestampMs, pointStart)) {
                val duplicate = last.time == normalizedTime &&
                    last.latitude == point.latitude &&
                    last.longitude == point.longitude
                if (duplicate || normalizedTime < last.time) return currentTrail
            }
            val trackerId = point.trackerId.trim()
            if (trackerId.isEmpty()) return currentTrail
            val queued = QueuedLocation(
                id = 0L,
                trackerId = trackerId,
                time = normalizedTime,
                latitude = point.latitude,
                longitude = point.longitude,
                altitude = null,
                speed = null,
                bearing = null,
                accuracy = point.accuracyMeters,
                sat = null,
                prov = PROVENANCE_REMOTE_STREAM,
                dist = null,
                startTimestampMs = pointStart,
            )
            return fitToCount(currentTrail + queued, trailPointLimit)
        }

        private fun isDifferentSession(tailStart: Long?, pointStart: Long?): Boolean {
            return tailStart != null && pointStart != null && tailStart != pointStart
        }

        suspend fun loadLocalOverlay(
            plan: TrackerMapTrailReloadPlan,
            currentSingleTrail: List<QueuedLocation>,
            currentMultiTrails: Map<String, List<QueuedLocation>>,
            loadQueue: suspend (String) -> List<QueuedLocation>,
        ): TrackerMapTrailLoadResult {
            val queueOverlays = queueOverlayFor(plan.overlayTrackerId, loadQueue)
            return TrackerMapTrailLoadResult(
                serverTrails = if (plan.source == TrackerMapTrailSource.MULTI_SERVER) {
                    currentMultiTrails
                } else {
                    emptyMap()
                },
                queueOverlaysByTracker = queueOverlays,
                singleTrailSeed = currentSingleTrail,
                authoritativeServerTrackerIds = emptySet(),
            )
        }

        private suspend fun queueOverlayFor(
            overlayTrackerId: String?,
            loadQueue: suspend (String) -> List<QueuedLocation>,
        ): Map<String, List<QueuedLocation>> {
            val normalized = overlayTrackerId?.trim().orEmpty()
            if (normalized.isEmpty()) return emptyMap()
            val rows = loadQueue(normalized)
            if (rows.isEmpty()) return emptyMap()
            return mapOf(normalized to rows)
        }

        fun shouldProceedReload(input: TrailReloadGuardInput): Boolean {
            if (input.trailSize == 0) return true
            val activeId = input.displayedTrackerId.trim()
            if (activeId.isEmpty()) return true
            if (input.trailReloadPlan.source == TrackerMapTrailSource.MULTI_SERVER) return true
            return !(input.runtimeRunning && input.trailReloadPlan.source == TrackerMapTrailSource.SINGLE_QUEUE)
        }

        fun fitToCount(points: List<QueuedLocation>, target: Int): List<QueuedLocation> {
            if (target <= 0) return emptyList()
            if (points.size <= target) return points
            val segments = TrackerHistorySessionAttribution.segment(
                points = points,
                context = TrackerHistorySessionAttributionContext(),
            )
            if (segments.isEmpty()) return points
            if (segments.size == 1) return points.takeLast(target)

            val segmentIndices: List<List<Int>> = buildSegmentIndices(points, segments)
            val seenAttributable = segmentIndices.sumOf { it.size }
            if (seenAttributable == 0) return points.takeLast(target)

            val seenLens = segmentIndices.map { it.size }
            val floors = seenLens.map { min(2, it) }
            val floorSum = floors.sum()
            val totalAttributable = seenAttributable

            val effectiveTarget = target.coerceAtLeast(floorSum).coerceAtMost(totalAttributable)
            val extrasPool = totalAttributable - floorSum
            val remainingExtras = effectiveTarget - floorSum

            val allocations = IntArray(segmentIndices.size) { segIdx ->
                val length = seenLens[segIdx]
                val floor = floors[segIdx]
                val extraAvail = length - floor
                if (extrasPool == 0 || remainingExtras <= 0) {
                    floor
                } else {
                    val extra = ((remainingExtras.toDouble() * extraAvail) / extrasPool).roundToInt()
                    floor + extra.coerceIn(0, extraAvail)
                }
            }

            var overshoot = allocations.sum() - effectiveTarget
            while (overshoot > 0) {
                var bestIdx = -1
                var bestRoom = 0
                for (i in allocations.indices) {
                    val room = allocations[i] - floors[i]
                    if (room > bestRoom) {
                        bestRoom = room
                        bestIdx = i
                    }
                }
                if (bestIdx < 0) break
                allocations[bestIdx] -= 1
                overshoot -= 1
            }

            val kept = TreeSet<Int>()
            for ((segIdx, indices) in segmentIndices.withIndex()) {
                for (idx in uniformStrideKeep(indices, allocations[segIdx])) {
                    kept.add(idx)
                }
            }
            return points.filterIndexed { index, _ -> index in kept }
        }

        private fun normalizedIdsSignature(ids: Collection<String>): String {
            return ids
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .sorted()
                .joinToString(separator = ",")
        }

        private fun groupSelectionSignature(selection: TrackerMapGroupModeSelection): String {
            val trackerIds = selection.trackerIds
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .sorted()
                .joinToString(separator = ",")
            return "${selection.groupId.orEmpty()}|$trackerIds"
        }

        private fun buildSegmentIndices(
            points: List<QueuedLocation>,
            segments: List<TrackerHistorySessionSegment>,
        ): List<List<Int>> {
            val identityIndex = java.util.IdentityHashMap<QueuedLocation, Int>(points.size)
            for ((idx, point) in points.withIndex()) {
                identityIndex[point] = idx
            }
            return segments.map { segment ->
                segment.points.mapNotNull { identityIndex[it] }
            }
        }

        private fun uniformStrideKeep(indices: List<Int>, targetCount: Int): List<Int> {
            val n = indices.size
            if (n <= 0 || targetCount >= n) return indices
            if (targetCount <= 1) return listOf(indices.last())
            if (targetCount == 2) return listOf(indices.first(), indices.last())
            val step = (n - 1).toDouble() / (targetCount - 1).toDouble()
            val seen = LinkedHashSet<Int>(targetCount)
            for (k in 0 until targetCount) {
                val pos = (k * step).roundToInt().coerceIn(0, n - 1)
                seen.add(indices[pos])
            }
            seen.add(indices.last())
            return seen.sorted()
        }

        fun historyWindowForTracker(
            trackerId: String,
            trackers: List<Tracker>,
        ): TrackerHistoryWindow {
            val tracker = trackers.firstOrNull { it.id.trim() == trackerId.trim() }
            return TrackerHistoryWindowResolver.fromTracker(tracker)
        }

        fun historyTrackerIdsForRender(
            state: TrackerMapUiState,
            plan: TrackerMapStreamingPlan,
            visibleTrackerIds: Set<String>?,
        ): Set<String> {
            return when (state.mode) {
                TrackerMapDisplayMode.SINGLE_SESSION -> {
                    val id = plan.displayedTrackerId.trim().ifBlank { plan.selectedTrackerId.trim() }
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

        fun unpublishedOverlaysByTracker(
            ingestor: HistoryTrunkIngestor,
            trackerIds: Collection<String>,
            trackers: List<Tracker>,
        ): Map<String, List<QueuedLocation>> {
            return trackerIds
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .associateWith { trackerId ->
                    val window = historyWindowForTracker(trackerId, trackers)
                    ingestor.unpublishedOverlay(TrackerHistoryKey(trackerId, window))
                }
                .filterValues { it.isNotEmpty() }
        }

        fun trailsFromSnapshots(
            state: TrackerMapUiState,
            plan: TrackerMapStreamingPlan,
            snapshots: Map<TrackerHistoryKey, TrackerHistorySnapshot>,
            trackers: List<Tracker>,
            trailPointLimit: Int,
            visibleTrackerIds: Set<String>? = null,
            unpublishedOverlaysByTracker: Map<String, List<QueuedLocation>> = emptyMap(),
            previousSingleTrail: List<QueuedLocation>? = null,
            previousMultiTrails: Map<String, List<QueuedLocation>>? = null,
            previousRemoteLastPoints: Map<String, TrackPoint>? = null,
        ): TrailsFromHistory {
            val priorSingle = previousSingleTrail ?: emptyList()
            val priorMulti = previousMultiTrails ?: emptyMap()
            val priorRemotes = previousRemoteLastPoints ?: emptyMap()
            val incompleteTrunks = mutableSetOf<String>()
            val degradedTrunks = mutableSetOf<String>()
            val trails = when (state.mode) {
                TrackerMapDisplayMode.SINGLE_SESSION -> {
                    val trackerId = plan.displayedTrackerId.trim()
                        .ifBlank { plan.selectedTrackerId.trim() }
                    val window = historyWindowForTracker(trackerId, trackers)
                    val key = TrackerHistoryKey(trackerId, window)
                    val snapshot = snapshots[key]
                    val tracker = trackers.firstOrNull { it.id.trim() == trackerId.trim() }
                    markSnapshotFlags(snapshot, tracker, trackerId, incompleteTrunks, degradedTrunks)
                    val mapped = TrackerHistoryRenderMapper.toQueuedLocations(snapshot, trailPointLimit)
                    val preserved = preserveActiveSessionTrailWhenMappedEmpty(
                        mappedTrail = mapped,
                        stateTrail = priorSingle,
                        trackerId = trackerId,
                        activeSessionStartMs = activeSessionStartMsForTracker(state.runtime, trackerId),
                    )
                    val withUnpublished = appendUnpublishedOverlay(
                        preserved,
                        unpublishedOverlaysByTracker[trackerId].orEmpty(),
                    )
                    val trail = mergeLiveDrawSingle(
                        mappedTrail = withUnpublished,
                        unpublishedOverlay = emptyList(),
                        remoteLastPoint = priorRemotes[trackerId],
                        runtime = state.runtime,
                        displayedTrackerId = trackerId,
                        trailPointLimit = trailPointLimit,
                    )
                    TrailsFromHistory(
                        trail = trail,
                        allQueueTrailsByTracker = priorMulti,
                        degradedTrackerIds = degradedTrunks.toSet(),
                    ) to TrackerHistoryDiagnostics.TrailsDrawSummary(
                        singleCount = trail.size,
                        singleTime = TrackerHistoryDiagnostics.queuedTimeRange(trail),
                        multiSizes = TrackerHistoryDiagnostics.mapSizes(priorMulti),
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
                            val mapped = TrackerHistoryRenderMapper.toQueuedLocations(snapshot, trailPointLimit)
                            val preserved = preserveActiveSessionTrailWhenMappedEmpty(
                                mappedTrail = mapped,
                                stateTrail = priorMulti[trackerId].orEmpty(),
                                trackerId = trackerId,
                                activeSessionStartMs = activeSessionStartMsForTracker(state.runtime, trackerId),
                            )
                            appendUnpublishedOverlay(
                                preserved,
                                unpublishedOverlaysByTracker[trackerId].orEmpty(),
                            )
                        }
                    val mergedMulti = mergeLiveDrawMulti(
                        mappedTrails = multi,
                        unpublishedOverlaysByTracker = emptyMap(),
                        remoteLastPoints = priorRemotes,
                        runtime = state.runtime,
                        mode = state.mode,
                        groupTrackerIds = plan.groupTrackerIds,
                        trailPointLimit = trailPointLimit,
                    )
                    val activeId = plan.displayedTrackerId.trim()
                        .ifBlank { plan.selectedTrackerId.trim() }
                    val activeTrail = mergedMulti[activeId].orEmpty()
                    TrailsFromHistory(
                        trail = activeTrail,
                        allQueueTrailsByTracker = mergedMulti,
                        degradedTrackerIds = degradedTrunks.toSet(),
                    ) to TrackerHistoryDiagnostics.TrailsDrawSummary(
                        singleCount = activeTrail.size,
                        singleTime = TrackerHistoryDiagnostics.queuedTimeRange(activeTrail),
                        multiSizes = TrackerHistoryDiagnostics.mapSizes(mergedMulti),
                        incompleteTrackerIds = incompleteTrunks.toSet(),
                        degradedTrackerIds = degradedTrunks.toSet(),
                    )
                }
            }
            val (result, drawSummary) = trails
            TrackerHistoryDiagnostics.logDrawApply(
                mode = state.mode.name,
                displayedTrackerId = plan.displayedTrackerId.ifBlank { plan.selectedTrackerId },
                trails = drawSummary,
                skipClientWindowFilter = emptySet(),
            )
            return result
        }

        fun syncRuntimeHead(
            runtime: TrackingRuntimeSnapshot,
            plan: TrackerMapStreamingPlan,
            liveHeads: LiveHeadStore,
        ) {
            if (!runtime.localRecordingActive) return
            val trackerId = runtime.locallyRecordedTrackerId.trim()
            if (trackerId.isEmpty()) return
            val shouldOverlay = when (plan.mode) {
                TrackerMapDisplayMode.SINGLE_SESSION -> {
                    val displayed = plan.displayedTrackerId.trim().ifBlank { plan.selectedTrackerId.trim() }
                    displayed == trackerId
                }
                TrackerMapDisplayMode.ALL_QUEUE,
                TrackerMapDisplayMode.GROUP_PLACEHOLDER -> trackerId in plan.localOverlayTrackerIds
            }
            if (!shouldOverlay) return
            val point = runtimeHeadTrackPoint(runtime, trackerId) ?: return
            liveHeads.upsert(point)
            TrackerHistoryDiagnostics.logRuntimeHead(trackerId, "live_head", point.timeMs)
        }

        fun commitQueueOverlays(
            queueOverlaysByTracker: Map<String, List<QueuedLocation>>,
            trackers: List<Tracker>,
            dispatcher: TrackerHistoryIntentDispatcher,
            activeSessionStartMsFor: (String) -> Long?,
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
                        activeSessionStartMs = activeSessionStartMsFor(trackerId),
                    ),
                )
                if (result.publishesSnapshot()) {
                    committed += "$trackerId:${overlay.size}@${window.normalizedKey}"
                }
            }
            if (committed.isNotEmpty()) {
                GeoVaultCaptureLog.i(
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
            point: TrackPoint,
            trackers: List<Tracker>,
            dispatcher: TrackerHistoryIntentDispatcher,
            activeSessionStartMs: Long?,
        ): Boolean {
            val trackerId = point.trackerId.trim()
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
            if (result.publishesSnapshot()) return true
            if (batch.points.isEmpty()) return false
            return result is TrackerHistoryTransactionResult.DeferredEmpty
        }

        fun activeSessionStartMsForTracker(
            runtime: TrackingRuntimeSnapshot,
            trackerId: String,
        ): Long? {
            if (!runtime.localRecordingActive) return null
            val normalized = trackerId.trim()
            if (normalized.isEmpty() || normalized != runtime.locallyRecordedTrackerId.trim()) return null
            return runtime.sessionStartTimeMs.takeIf { it > 0L }
        }

        internal fun runtimeHeadTrackPoint(
            runtime: TrackingRuntimeSnapshot,
            trackerId: String,
        ): TrackPoint? {
            val lat = runtime.lastTrackedLatitude ?: return null
            val lon = runtime.lastTrackedLongitude ?: return null
            val runtimeTs = runtime.lastTrackedTimestampMs
            if (runtimeTs <= 0L) return null
            return TrackPoint(
                trackerId = trackerId,
                timeMs = runtimeTs,
                latitude = lat,
                longitude = lon,
                provenance = TrackPointSource.LOCAL_GPS,
                sessionId = runtime.sessionStartTimeMs.takeIf { it > 0L },
                accuracyMeters = runtime.lastAccuracyMeters,
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

        fun resolveTrunkRefresh(input: TrackerHistoryRefreshInput): TrackerHistoryRefreshDecision {
            return when (input.cause) {
                TrackerHistoryRefreshCause.TrackerSwitch,
                TrackerHistoryRefreshCause.ModeSwitch,
                TrackerHistoryRefreshCause.WindowChanged,
                TrackerHistoryRefreshCause.ColdStart,
                TrackerHistoryRefreshCause.HistoryCleared,
                TrackerHistoryRefreshCause.RosterChanged -> TrackerHistoryRefreshDecision(true, input.cause.name)

                TrackerHistoryRefreshCause.UploadSuccess -> TrackerHistoryRefreshDecision(
                    shouldRefresh = input.visibleRowsUploaded,
                    reason = if (input.visibleRowsUploaded) "visible_upload" else "no_visible_rows",
                )

                TrackerHistoryRefreshCause.Resume,
                TrackerHistoryRefreshCause.PeriodicRecording -> {
                    val last = input.lastTrunkFetchedAtMs
                    val stale = last == null || input.nowMs - last >= input.staleAfterMs
                    TrackerHistoryRefreshDecision(stale, if (stale) "stale_trunk" else "fresh_trunk")
                }

                TrackerHistoryRefreshCause.CosmeticTick,
                TrackerHistoryRefreshCause.LivePoint -> TrackerHistoryRefreshDecision(false, "overlay_or_cosmetic_only")
            }
        }

        fun preserveActiveSessionTrailWhenMappedEmpty(
            mappedTrail: List<QueuedLocation>,
            stateTrail: List<QueuedLocation>,
            trackerId: String,
            activeSessionStartMs: Long?,
        ): List<QueuedLocation> {
            if (mappedTrail.isNotEmpty()) return mappedTrail
            val activeStart = activeSessionStartMs?.takeIf { it > 0L } ?: return mappedTrail
            val normalizedId = trackerId.trim()
            if (normalizedId.isEmpty()) return mappedTrail
            val activeCurrent = stateTrail.filter { point ->
                point.trackerId.trim() == normalizedId &&
                    point.startTimestampMs == activeStart
            }
            if (activeCurrent.isEmpty()) return mappedTrail
            return activeCurrent
        }

        fun mergeActiveSessionCoverageIntoTrunk(
            serverTrunk: List<QueuedLocation>,
            currentTrail: List<QueuedLocation>,
            trackerId: String,
            activeSessionStartMs: Long?,
            trailPointLimit: Int,
        ): List<QueuedLocation> {
            val normalizedId = trackerId.trim()
            if (normalizedId.isEmpty() || activeSessionStartMs == null) return serverTrunk
            val activeCurrent = currentTrail.filter { point ->
                point.trackerId.trim() == normalizedId &&
                    point.startTimestampMs == activeSessionStartMs
            }
            if (activeCurrent.isEmpty()) return serverTrunk
            val loadedKeys = serverTrunk.map(::equivalentPointKey).toSet()
            val missingActive = activeCurrent.filter { point -> equivalentPointKey(point) !in loadedKeys }
            if (missingActive.isEmpty()) return serverTrunk
            val merged = (serverTrunk + missingActive)
                .distinctBy(::equivalentPointKey)
                .sortedBy { it.time }
            return fitToCount(merged, trailPointLimit)
        }

        fun mergeActiveSessionCoverageIntoTrunkBatch(
            batch: TrackerHistorySourceBatch,
            currentTrail: List<QueuedLocation>,
            activeSessionStartMs: Long?,
            trailPointLimit: Int,
        ): TrackerHistorySourceBatch {
            if (batch.points.isEmpty()) return batch
            val queued = batch.points.map { it.toQueuedLocation() }
            val merged = mergeActiveSessionCoverageIntoTrunk(
                serverTrunk = queued,
                currentTrail = currentTrail,
                trackerId = batch.trackerId,
                activeSessionStartMs = activeSessionStartMs,
                trailPointLimit = trailPointLimit,
            )
            if (merged.size == queued.size) return batch
            val existingByKey = batch.points.associateBy { it.key }
            val points = merged.map { loc ->
                val key = TrackerHistoryPointKey.from(
                    trackerId = loc.trackerId,
                    timestampMs = loc.time,
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    startTimestampMs = loc.startTimestampMs,
                )
                existingByKey[key] ?: TrackerHistoryPoint.fromQueuedLocation(
                    point = loc,
                    provenance = provenanceForQueuedLocation(loc, batch.sourceKind),
                )
            }
            return batch.copy(points = points.sortedBy { it.timestampMs })
        }

        fun mergeLiveDrawSingle(
            mappedTrail: List<QueuedLocation>,
            unpublishedOverlay: List<QueuedLocation>,
            remoteLastPoint: TrackPoint?,
            runtime: TrackingRuntimeSnapshot,
            displayedTrackerId: String,
            trailPointLimit: Int,
        ): List<QueuedLocation> {
            val trackerId = displayedTrackerId.trim().ifBlank { runtime.locallyRecordedTrackerId.trim() }
            val withOverlay = appendUnpublishedOverlay(mappedTrail, unpublishedOverlay)
            val withRemote = appendRemoteHeadIfNewer(withOverlay, trackerId, remoteLastPoint)
            return MapRenderMath.singleTrailWithLocalRuntimeOverlay(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                runtime = runtime,
                displayedTrackerId = displayedTrackerId,
                trail = withRemote,
                trailPointLimit = trailPointLimit,
            )
        }

        fun mergeLiveDrawMulti(
            mappedTrails: Map<String, List<QueuedLocation>>,
            unpublishedOverlaysByTracker: Map<String, List<QueuedLocation>>,
            remoteLastPoints: Map<String, TrackPoint>,
            runtime: TrackingRuntimeSnapshot,
            mode: TrackerMapDisplayMode,
            groupTrackerIds: Set<String>,
            trailPointLimit: Int,
        ): Map<String, List<QueuedLocation>> {
            val trackerIds = mappedTrails.keys + unpublishedOverlaysByTracker.keys + remoteLastPoints.keys
            val merged = trackerIds
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .associateWith { trackerId ->
                    val withOverlay = appendUnpublishedOverlay(
                        mappedTrails[trackerId].orEmpty(),
                        unpublishedOverlaysByTracker[trackerId].orEmpty(),
                    )
                    appendRemoteHeadIfNewer(withOverlay, trackerId, remoteLastPoints[trackerId])
                }
            return MapRenderMath.allQueueTrailsWithLocalRuntimeOverlay(
                mode = mode,
                runtime = runtime,
                groupTrackerIds = groupTrackerIds,
                allQueueTrailsByTracker = merged,
                trailPointLimit = trailPointLimit,
            )
        }

        fun appendUnpublishedOverlay(
            mappedTrail: List<QueuedLocation>,
            unpublishedOverlay: List<QueuedLocation>,
        ): List<QueuedLocation> {
            if (unpublishedOverlay.isEmpty()) return mappedTrail
            val existing = mappedTrail.map { equivalentPointKey(it) }.toSet()
            val extra = unpublishedOverlay.filter { equivalentPointKey(it) !in existing }
            if (extra.isEmpty()) return mappedTrail
            return (mappedTrail + extra).sortedBy { it.time }
        }

        fun appendRemoteHeadIfNewer(
            trail: List<QueuedLocation>,
            trackerId: String,
            remoteLastPoint: TrackPoint?,
        ): List<QueuedLocation> {
            if (remoteLastPoint == null) return trail
            if (remoteLastPoint.provenance != TrackPointSource.REMOTE_STREAM) return trail
            val normalizedId = trackerId.trim()
            if (normalizedId.isEmpty()) return trail
            val last = trail.lastOrNull()
            if (last != null && last.time >= remoteLastPoint.timeMs) return trail
            return trail + remoteLastPoint.toQueuedLocation(normalizedId)
        }

        private fun provenanceForQueuedLocation(
            point: QueuedLocation,
            sourceKind: TrackerHistorySourceKind,
        ): TrackerHistoryProvenance {
            if (isServerHistory(point)) {
                return TrackerHistoryProvenance.SERVER_GEOMETRY
            }
            return when (sourceKind) {
                TrackerHistorySourceKind.FILTERED_SERVER_TRUNK -> TrackerHistoryProvenance.SERVER_GEOMETRY
                TrackerHistorySourceKind.DEGRADED_LOCAL_ONLY,
                TrackerHistorySourceKind.LOCAL_QUEUE -> TrackerHistoryProvenance.LOCAL_QUEUE
                TrackerHistorySourceKind.LOCAL_LIVE -> TrackerHistoryProvenance.LOCAL_LIVE
                TrackerHistorySourceKind.REMOTE_STREAM -> TrackerHistoryProvenance.REMOTE_STREAM
            }
        }

        private fun equivalentPointKey(point: QueuedLocation): String {
            return listOf(
                point.trackerId.trim(),
                point.time.toString(),
                point.latitude.toString(),
                point.longitude.toString(),
            ).joinToString("|")
        }

        private fun TrackPoint.toQueuedLocation(trackerId: String): QueuedLocation {
            return QueuedLocation(
                id = 0L,
                trackerId = trackerId,
                time = timeMs,
                latitude = latitude,
                longitude = longitude,
                altitude = null,
                speed = gpsSpeedMps,
                bearing = gpsBearingDeg,
                accuracy = accuracyMeters,
                sat = null,
                prov = PROVENANCE_REMOTE_STREAM,
                dist = null,
                startTimestampMs = null,
            )
        }
    }

    internal fun abandonStaleReload(reason: TrackerMapTrailReloadReason) {
        lastTrailLoadSeed = null
        disarmReloadFit(reason)
    }

    internal fun armReloadFit(reason: TrackerMapTrailReloadReason, generation: Long) {
        if (!reason.allowServerHistoryFetch) return
        reloadFitArmed = true
        reloadFitArmedGeneration = generation
    }

    internal fun disarmReloadFit(reason: TrackerMapTrailReloadReason) {
        if (reason.allowServerHistoryFetch) reloadFitArmed = false
    }

    internal fun consumeReloadFitIfLanded(
        reason: TrackerMapTrailReloadReason,
        hasData: Boolean,
        anyLockActive: Boolean,
        currentGeneration: Long,
    ): Boolean {
        if (!reason.allowServerHistoryFetch || !reloadFitArmed || !hasData) return false
        reloadFitArmed = false
        return !anyLockActive && currentGeneration == reloadFitArmedGeneration
    }
}
