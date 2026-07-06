package com.geovault.tracker.map

import android.app.Application
import com.geovault.common.logging.CaptureLogThrottle
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.AppError
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.SelectedTrackerPrefs
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.history.*
import com.geovault.tracker.presentation.*
import com.geovault.tracker.streaming.StreamingConfig
import com.geovault.tracker.streaming.StreamingDiagnostics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock

/**
 * Owns fetching and merging trail geometry: coalescing reload requests behind a single-flight
 * queue, choosing between server/queue/cache sources per [TrackerMapTrailReloadReason], the
 * local-Room-queue preload/seed paths that keep the map from flashing empty on cold launch, and
 * arming the post-reload camera re-fit via [PendingReloadCameraFit]. Network fetches run
 * unlocked; only the final state commit is serialized against the live point consumer through
 * `trailCommitLock` (see the MUTEX-SCOPE note below), so a slow or offline fetch here can never
 * starve incoming live points.
 */
internal class MapTrailReloadSubsystem(private val rt: TrackerMapRuntime) {
    // Reload-cycle bookkeeping nothing outside this file touches directly.
    // `lastTrailLoadSeed` is the one exception -- two other subsystems need to invalidate it on
    // events that make the previous seed meaningless (a context reset, a history clear) without
    // themselves knowing anything about reload internals; see [invalidateLoadedSeed].
    private var runtimeTrailReloadJob: Job? = null
    private var runtimeTrailReloadPendingReason: TrackerMapTrailReloadReason? = null
    private var nextTrailReloadId: Long = 1L
    private var lastTrailLoadSeed: String? = null
    private val geometryLoadingTracker = TrackerMapGeometryLoadingTracker(
        onLoadingChanged = ::setGeometryLoading,
    )
    private val geometryReloadCircuitBreaker = MapGeometryReloadCircuitBreaker()

    /**
     * Invalidates the last-loaded trail seed so the next reload request can't be skipped as a
     * no-op duplicate. Called by other subsystems after an event that makes the previous seed
     * meaningless (a context reset, a history clear) without those callers needing to know
     * anything about how reload seeds are computed.
     */
    internal fun invalidateLoadedSeed() {
        lastTrailLoadSeed = null
    }

    private fun setGeometryLoading(isLoading: Boolean) {
        var changed = false
        rt.stateHub.uiStateMutable.update { current ->
            if (current.isGeometryLoading == isLoading) {
                current
            } else {
                changed = true
                current.copy(isGeometryLoading = isLoading)
            }
        }
        if (changed) {
            GeoVaultCaptureLog.d(TrackerMapViewModel.TAG, "map_update vm_geometry_loading to=$isLoading")
        }
    }

    // MUTEX-SCOPE: `trailCommitLock` is shared with the live track-point consumer
    // (see `MapStreamingSubsystem`'s `pointEventChannel` loop) so that a reload's
    // final state commit and a point's state commit can never interleave into an
    // inconsistent `uiStateMutable` value. Previously the *entire* reload — including
    // the server geometry fetch — ran inside this lock, so a slow or offline network
    // call held the mutex for as long as the request was in flight and starved the
    // point consumer for that whole time (observed as "recording device stopped
    // publishing its own points until the app was reopened"). The fetch itself does
    // not touch `uiStateMutable`, so it is intentionally run unlocked; only the final
    // commit (queue-overlay writeback + the `uiStateMutable.update` block) needs the
    // lock, and that block's own `trailSeedForState(latest) != seed` staleness check
    // is what protects it against state having moved on while the fetch was in flight.
    private suspend fun reloadTrailFromDatabase(reason: TrackerMapTrailReloadReason) {
        val planResult = planAndGuardReload(reason) ?: return
        val loaded = fetchReloadTrails(planResult)
        val mergeCommitted = commitReloadResult(planResult, loaded)
        applyCameraFitAfterReload(reason, mergeCommitted)
    }

    /**
     * Planning/guard phase: resolves what (if anything) should be reloaded for [reason],
     * running every early-exit check (stale-data guard, server-refresh staleness policy,
     * skip-source reconciliation, seed dedupe) before any network fetch is attempted.
     * Returns `null` if the reload should not proceed; otherwise returns the frozen
     * [ReloadPlanContext] the fetch/commit phases operate on.
     */
    private suspend fun planAndGuardReload(reason: TrackerMapTrailReloadReason): ReloadPlanContext? {
        val state = rt.stateHub.uiStateMutable.value
        val reloadId = nextTrailReloadId++
        GeoVaultCaptureLog.i(
            TrackerMapViewModel.TAG,
            "map_update vm_reload_start reloadId=$reloadId reason=$reason mode=${state.mode} displayed=${state.displayedTrackerId.trim()} " +
                "selected=${state.runtime.selectedTrackerId.trim()} localActive=${state.runtime.localRecordingActive} " +
                "trail=${state.trail.trailSummary()} multi=${state.allQueueTrailsByTracker.mapSizes()}"
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
            trailSize = state.trail.size,
            runtimeRunning = state.runtime.localRecordingActive,
            displayedTrackerId = activeTrackerId,
            trailReloadPlan = sessionPlan.trailReloadPlan,
        )
        if (!TrackerMapTrailReloadGuardPolicy.shouldProceed(guardInput)) {
            GeoVaultCaptureLog.d(
                TrackerMapViewModel.TAG,
                "map_update vm_reload_guard_skip reason=$reason mode=${state.mode} trailSize=${state.trail.size} " +
                    "runtimeRunning=${state.runtime.localRecordingActive} source=${sessionPlan.trailReloadPlan.source}"
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
                ?: sessionPlan.displayedTrackerId.trim().ifBlank { state.runtime.selectedTrackerId.trim() }
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
                TrackerHistoryRefreshPolicy.resolve(
                    TrackerHistoryRefreshInput(
                        cause = refreshCause,
                        nowMs = nowMs,
                        lastTrunkFetchedAtMs = lastTrunkMs,
                        trackerIdForStaleCheck = staleTrackerId.takeIf { it.isNotEmpty() },
                        isRecording = state.runtime.localRecordingActive,
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
        // Cached geometry is diagnostic-only during reload planning. The render pipeline must
        // not publish preload/intermediate states; a trail changes only through the immutable
        // snapshot commit below.
        val preloadedTrail = preloadedSingleTrackerTrailFromCacheOrNull(
            mode = state.mode,
            activeTrackerId = activeTrackerId,
            reloadId = reloadId,
        )
        if (preloadedTrail != null) {
            GeoVaultCaptureLog.i(
                TrackerMapViewModel.TAG,
                "map_update vm_reload_preload_observed reloadId=$reloadId tracker=$activeTrackerId " +
                    "points=${preloadedTrail.trailSummary()} reason=$reason action=defer_to_snapshot"
            )
        }
        if (!reason.allowsSource(sessionPlan.trailReloadPlan.source)) {
            rt.trailCommitLock.withCommitLock {
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
                        "displayed=${sessionPlan.displayedTrackerId} trail=${state.trail.size}"
                )
            }
            return null
        }
        // RE-FIT AFTER FETCH: every reload that legitimately hit the server can move state.trail
        // arbitrarily far from whatever the camera is currently framing (process death + resume,
        // launch with a stale runtime GPS coord, switching to a different selected tracker, etc).
        // Without this flag the fit-after-reload path only triggers from explicit map context
        // change handlers — leaving the camera frozen on stale bounds even though the trail data
        // it was sized to is no longer in state. Treat any server-fetching reload as the caller
        // having implicitly asked the camera to re-fit when the new data lands.
        if (reason.allowServerHistoryFetch) {
            rt.pendingReloadCameraFit.arm(reason, rt.cameraCoordinator.generation)
        }
        val seed = TrackerMapReloadSeedPolicy.trailSeed(
            TrackerMapTrailSeedInput(
                mode = state.mode,
                runtimeRunning = state.runtime.localRecordingActive,
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
        val existingTrailMinTimeMs = planSourceState.trail.minOfOrNull { it.time }
        val existingMultiMinTimes = planSourceState.allQueueTrailsByTracker
            .mapValues { (_, pts) -> pts.minOfOrNull { it.time } }
            .filterValues { it != null }
            .mapValues { it.value!! }
        return ReloadPlanContext(
            reloadId = reloadId,
            reason = reason,
            seed = seed,
            groupSelection = groupSelection,
            rosterTrackerIds = rosterTrackerIds,
            plan = plan,
            existingTrailMinTimeMs = existingTrailMinTimeMs,
            existingMultiMinTimes = existingMultiMinTimes,
        )
    }

    // SINGLE_SERVER + LOCAL OVERLAY: when the displayed tracker is the locally-recorded one,
    // server geometry alone can lag the live recording (uploads are async). The loader pairs
    // the server fetch with the local DB queue (returned in queueOverlaysByTracker) so the
    // merge has authoritative recent fixes to splice on top of server history.
    // MULTI_SERVER mirrors this: server geometry is loaded for every group/roster member and
    // returned untouched in serverTrails; the locally-recorded tracker's queue rows arrive in
    // queueOverlaysByTracker and are spliced as live-overlay candidates by the merge. They
    // never replace the server entry, so the multi view always retains real history for every
    // member — including the recording user.
    private suspend fun fetchReloadTrails(planResult: ReloadPlanContext): TrackerMapTrailLoadResult {
        val loaded = TrackerMapTrailLoader.load(
            plan = planResult.plan,
            existingTrailMinTimeMs = planResult.existingTrailMinTimeMs,
            existingMultiMinTimes = planResult.existingMultiMinTimes,
            ops = rt.trailLoaderOps,
        )
        GeoVaultCaptureLog.i(
            TrackerMapViewModel.TAG,
            "map_update vm_reload_loaded reloadId=${planResult.reloadId} reason=${planResult.reason} source=${planResult.plan.source} " +
                "single=${loaded.singleTrailSeed.trailSummary()} server=${loaded.serverTrails.mapSizes()} " +
                "queueOverlays=${loaded.queueOverlaysByTracker.mapSizes()} authoritative=${loaded.authoritativeServerTrackerIds.sorted()}"
        )
        return loaded
    }

    /**
     * Commit phase: writes the fetched [loaded] trails back into `uiStateMutable`, guarded by
     * `trailCommitLock` against the live point consumer (see the class-level MUTEX-SCOPE note).
     * Returns `null` if the commit was skipped because state moved on since [planResult] was
     * computed (`trailSeedForState(latest) != seed`).
     */
    private suspend fun commitReloadResult(
        planResult: ReloadPlanContext,
        loaded: TrackerMapTrailLoadResult,
    ): MergedTrailResult? {
        val (reloadId, reason, seed, groupSelection, rosterTrackerIds, plan) = planResult
        val trackers = rt.dependencies.trackerManagementStateStore.trackers.value
        // LOCK SCOPE: only the write-back of what the (now-completed) fetch produced needs
        // exclusivity against the point consumer — see the class-level MUTEX-SCOPE note.
        var mergeCommitted: MergedTrailResult? = null
        rt.trailCommitLock.withCommitLock {
            TrackerMapHistoryUiSync.commitQueueOverlays(
                queueOverlaysByTracker = loaded.queueOverlaysByTracker,
                trackers = trackers,
                dispatcher = rt.dependencies.historyIntentDispatcher,
                activeSessionStartMs = rt.currentActiveSessionStartMs(),
            )
            rt.stateHub.uiStateMutable.update { latest ->
                if (trailSeedForState(latest) != seed) {
                    return@update latest
                }
                val sessionPlan = rt.projectSession(
                    state = latest,
                    groupSelection = groupSelection,
                    visibleRosterTrackerIds = rosterTrackerIds,
                )
                val trailsState = rt.display.applyHistoryTrailsToState(latest, sessionPlan)
                mergeCommitted = MergedTrailResult(
                    trail = trailsState.trail,
                    multiTrails = trailsState.allQueueTrailsByTracker,
                )
                val activeTrackerId = sessionPlan.displayedTrackerId.ifBlank { latest.runtime.selectedTrackerId }
                val activeWindow = TrackerMapHistoryUiSync.historyWindowForTracker(
                    activeTrackerId,
                    trackers,
                )
                val activeSnapshot = rt.dependencies.historyRepository.snapshotFor(
                    com.geovault.tracker.history.TrackerHistoryKey(activeTrackerId, activeWindow),
                )
                GeoVaultCaptureLog.i(
                    TrackerMapViewModel.TAG,
                    "map_update vm_reload_commit reloadId=$reloadId reason=$reason source=${plan.source} " +
                        "trail=${trailsState.trail.trailSummary()} multi=${trailsState.allQueueTrailsByTracker.mapSizes()} " +
                        TrackerHistoryDiagnostics.snapshotLine(activeSnapshot),
                )
                trailsState.copy(
                    currentGroupId = if (latest.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
                        plan.resolvedGroupId
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
            lastTrailLoadSeed = null
        }
        return mergeCommitted
    }

    /**
     * Camera-fit phase: re-frames the camera once a reload's commit has actually landed. When
     * the commit was skipped (`mergeCommitted == null`), disarms any `pendingReloadCameraFit`
     * this reload armed during planning instead of leaving it to dangle for a later, unrelated
     * reload to consume.
     */
    private fun applyCameraFitAfterReload(reason: TrackerMapTrailReloadReason, mergeCommitted: MergedTrailResult?) {
        val finalMerge = mergeCommitted ?: run {
            // STALE-COMMIT FIT-FLAG LEAK: this reload armed `pendingReloadCameraFit` above (if it
            // fetched from the server) expecting to consume it once its own commit landed. When
            // the commit is skipped because state moved on before the fetch returned
            // (`trailSeedForState(latest) != seed`), that never happens -- disarm it here so it
            // can't dangle for some later, unrelated reload to consume instead, producing a
            // spurious camera re-fit with no connection to what the user actually did.
            rt.pendingReloadCameraFit.disarm(reason)
            return
        }
        val hasData = finalMerge.trail.isNotEmpty() || finalMerge.multiTrails.isNotEmpty()
        val anyLockActive = rt.stateHub.uiStateMutable.value.hasAnyMapLockActive()
        if (
            rt.pendingReloadCameraFit.consumeIfLanded(
                reason = reason,
                hasData = hasData,
                anyLockActive = anyLockActive,
                currentGeneration = rt.cameraCoordinator.generation,
            )
        ) {
            // INSTANT after server-fetching reload: the InitialFit directive (or a prior
            // user-driven fit) has already framed the camera on the locally-preloaded
            // bounds. The server response typically nudges those bounds by a small delta;
            // animating that delta produces a visible jolt at first map open. moveCamera
            // snaps to the final framing in one frame, which is the right semantics for a
            // re-fit the user did not initiate.
            //
            // STREAMING-START LOCK FIGHT: when a map lock is already active (e.g. the selection
            // lock `StreamRosterResolver` engages the instant a stream starts), `consumeIfLanded`
            // returns false above and this explicit full-extent fit is skipped entirely -- the
            // "render-resync" collector reacting to this same commit's state/history change
            // republishes the reactive precedence-driven directive, which frames the camera on
            // the lock's target instead, so the two paths never fight over the camera.
            rt.context.requestFitTrail(TrackerMapFitTrailMode.Instant)
        }
    }

    private data class ReloadPlanContext(
        val reloadId: Long,
        val reason: TrackerMapTrailReloadReason,
        val seed: String,
        val groupSelection: TrackerMapGroupModeSelection,
        val rosterTrackerIds: Set<String>,
        val plan: TrackerMapTrailReloadPlan,
        val existingTrailMinTimeMs: Long?,
        val existingMultiMinTimes: Map<String, Long>,
    )

    internal data class MergedTrailResult(
        val trail: List<QueuedLocation>,
        val multiTrails: Map<String, List<QueuedLocation>>,
    )

    internal fun requestRuntimeTrailReload(reason: TrackerMapTrailReloadReason) {
        if (runtimeTrailReloadJob?.isActive == true) {
            runtimeTrailReloadPendingReason = runtimeTrailReloadPendingReason.mergedWith(reason)
            return
        }
        runtimeTrailReloadJob = rt.ports.viewModelScope.launch {
            var nextReason: TrackerMapTrailReloadReason? = reason
            while (nextReason != null) {
                val current = nextReason
                runtimeTrailReloadPendingReason = null
                reloadTrailFromDatabase(current)
                nextReason = runtimeTrailReloadPendingReason
            }
        }
    }

    /**
     * SINGLE-FLIGHT REOPEN LOAD: routes through the same `runtimeTrailReloadJob` queue as every
     * other reload trigger instead of invoking [reloadTrailFromDatabase] directly (the previous
     * behavior of [MapContextSubsystem.applyReopenDecision]'s single-tracker-load branch). A
     * direct call could run fully concurrently with an already in-flight queued reload — two
     * independent fetches racing to commit under `trailCommitLock`, with nothing stopping the
     * slower one from landing its now-stale trunk *after* the faster one's fresher commit, and
     * both unsynchronizedly stomping shared bookkeeping (`lastTrailLoadSeed`,
     * `rt.pendingReloadCameraFit`). Requesting through the queue means a concurrent reload either
     * runs this reason next (merged into the same job) or, if nothing is in flight, becomes the
     * sole active reload — exactly the "only one `reloadTrailFromDatabase` call in flight at a
     * time" invariant the queue exists to guarantee. Still suspends the caller until this reason
     * has actually been processed (joining whichever job ends up owning it), matching the
     * previous direct call's await-until-complete semantics.
     */
    internal suspend fun requestAndAwaitRuntimeTrailReload(reason: TrackerMapTrailReloadReason) {
        requestRuntimeTrailReload(reason)
        runtimeTrailReloadJob?.join()
    }

    private suspend fun reconcileLocalQueueOverlayForSkippedReload(
        reason: TrackerMapTrailReloadReason,
        plan: TrackerMapTrailReloadPlan,
    ) {
        val overlayTrackerId = plan.overlayTrackerId?.trim().orEmpty()
        if (overlayTrackerId.isEmpty()) return
        if (!rt.stateHub.uiStateMutable.value.runtime.localRecordingActive) return

        val initialState = rt.stateHub.uiStateMutable.value
        val loaded = TrackerMapTrailLoader.loadLocalOverlay(
            plan = plan,
            currentSingleTrail = initialState.trail,
            currentMultiTrails = initialState.allQueueTrailsByTracker,
            ops = rt.trailLoaderOps,
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

        TrackerMapHistoryUiSync.commitQueueOverlays(
            queueOverlaysByTracker = loaded.queueOverlaysByTracker,
            trackers = rt.dependencies.trackerManagementStateStore.trackers.value,
            dispatcher = rt.dependencies.historyIntentDispatcher,
            activeSessionStartMs = rt.currentActiveSessionStartMs(),
        )
        // DEAD-LEAK-PATH REMOVED: this whole function only runs when `allowsSource` returned
        // false for `plan.source`, which is only possible for a non-server-fetching reason (see
        // `allowsSource`'s invariant below) -- so `pendingReloadCameraFit` can never be armed or
        // consumable here by construction. This used to track the commit result solely to read
        // and consume that flag afterward; a read/consume here was structurally guaranteed to
        // always be a no-op, so both it and the now-pointless result tracking are removed.
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
            val trailsState = rt.display.applyHistoryTrailsToState(latest, latestPlan)
            GeoVaultCaptureLog.i(
                TrackerMapViewModel.TAG,
                "map_update vm_local_overlay_commit reason=$reason source=${latestPlan.trailReloadPlan.source} overlay=$overlayTrackerId " +
                    "queue=${queueOverlay.trailSummary()} trail=${trailsState.trail.trailSummary()} " +
                    "multi=${trailsState.allQueueTrailsByTracker.mapSizes()}",
            )
            trailsState
        }
    }

    internal fun setOfNotBlank(value: String?): Set<String> {
        val normalized = value?.trim().orEmpty()
        return normalized.takeIf { it.isNotEmpty() }?.let(::setOf).orEmpty()
    }

    private fun TrackerMapTrailReloadReason.allowsSource(source: TrackerMapTrailSource): Boolean {
        return when {
            allowServerHistoryFetch -> true
            allowMultiServerHistoryFetch && source == TrackerMapTrailSource.MULTI_SERVER -> true
            source == TrackerMapTrailSource.SINGLE_QUEUE -> true
            else -> false
        }
    }

    internal suspend fun loadQueueTrail(trackerId: String): List<QueuedLocation> {
        val normalizedTrackerId = trackerId.trim()
        if (normalizedTrackerId.isEmpty()) return emptyList()
        // Load up to TrackerMapViewModel.QUEUE_TRAIL_FETCH_LIMIT (mirrors TrackingService.MAX_QUEUE_SIZE) and then
        // apply session-aware decimation. The previous version asked the DAO for exactly
        // TrackerMapViewModel.TRAIL_POINT_LIMIT rows ordered by time DESC, which silently dropped the OLDEST points
        // when both sessions exceeded the cap — exactly the bug the recent_data_window=session
        // filter is supposed to prevent.
        val recent = withContext(Dispatchers.IO) {
            rt.dependencies.dao.getRecentChronologicalForTracker(normalizedTrackerId, TrackerMapViewModel.QUEUE_TRAIL_FETCH_LIMIT)
        }
        return recent
    }

    internal suspend fun loadSingleTrackerTrailFromServer(
        trackerId: String,
        existingTrailMinTimeMs: Long?,
    ): TrackerMapServerTrailResult {
        val normalizedId = trackerId.trim()
        return when (
            val geometryResult = rt.sessionRequestDeduper.loadOnce("single:geometry:$normalizedId") {
                geometryLoadingTracker.track {
                    fetchGeometryGuarded(setOf(normalizedId)) { rt.dependencies.trackerManagementRepository.loadTrackerGeometry(normalizedId) }
                }
            }
        ) {
            is RepositoryResult.Success -> {
                val batch = enrichTrunkBatchForActiveSession(
                    TrackerHistorySourceAdapters.filteredServerTrunk(geometryResult.data),
                    trackerId = normalizedId,
                )
                val transaction = rt.dependencies.historyIntentDispatcher.dispatch(
                    TrackerHistoryIntent.CommitTrunk(
                        batch = batch,
                        activeSessionStartMs = rt.currentActiveSessionStartMs(),
                    )
                )
                TrackerMapServerTrailResult(
                    trailsByTracker = mapOf(
                        normalizedId to TrackerHistoryRenderMapper.toQueuedLocations(
                            transaction.snapshot,
                            TrackerMapViewModel.TRAIL_POINT_LIMIT,
                        ),
                    ),
                    authoritativeTrackerIds = setOf(normalizedId).filter { it.isNotEmpty() }.toSet(),
                )
            }
            is RepositoryResult.Failure -> {
                val trackers = rt.dependencies.trackerManagementStateStore.trackers.value
                val window = TrackerMapHistoryUiSync.historyWindowForTracker(normalizedId, trackers)
                val queueTrail = loadQueueTrail(normalizedId)
                val batch = TrackerHistorySourceAdapters.degradedLocalOnlyTrunk(
                    trackerId = normalizedId,
                    window = window,
                    queuedLocations = queueTrail,
                )
                val transaction = rt.dependencies.historyIntentDispatcher.dispatch(
                    TrackerHistoryIntent.CommitTrunk(
                        batch = batch,
                        activeSessionStartMs = rt.currentActiveSessionStartMs(),
                    )
                )
                TrackerMapServerTrailResult(
                    trailsByTracker = mapOf(
                        normalizedId to TrackerHistoryRenderMapper.toQueuedLocations(
                            transaction.snapshot,
                            TrackerMapViewModel.TRAIL_POINT_LIMIT,
                        ),
                    ),
                    authoritativeTrackerIds = emptySet(),
                )
            }
        }
    }

    internal suspend fun loadTrailsForTrackerIds(
        trackerIds: Collection<String>,
        existingTrailMinTimeMsByTracker: Map<String, Long>,
    ): TrackerMapServerTrailResult {
        val normalizedIds = trackerIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (normalizedIds.isEmpty()) {
            return TrackerMapServerTrailResult(
                trailsByTracker = emptyMap(),
                authoritativeTrackerIds = emptySet(),
            )
        }
        val key = "multi:geometry:${normalizedIds.sorted().joinToString(",")}"
        return when (
            val result = rt.sessionRequestDeduper.loadOnce(key) {
                geometryLoadingTracker.track {
                    fetchGeometryGuarded(normalizedIds.toSet()) { rt.dependencies.trackerManagementRepository.loadTrackersGeometry(normalizedIds) }
                }
            }
        ) {
            is RepositoryResult.Success -> {
                val trackersById = result.data.associateBy { it.id.trim() }
                val authoritativeIds = trackersById.keys.intersect(normalizedIds.toSet())
                val trails = normalizedIds.associateWith { trackerId ->
                    val tracker = trackersById[trackerId]
                    if (tracker == null) {
                        val trackers = rt.dependencies.trackerManagementStateStore.trackers.value
                        val window = TrackerMapHistoryUiSync.historyWindowForTracker(trackerId, trackers)
                        val queueTrail = loadQueueTrail(trackerId)
                        val batch = TrackerHistorySourceAdapters.degradedLocalOnlyTrunk(
                            trackerId = trackerId,
                            window = window,
                            queuedLocations = queueTrail,
                        )
                        val transaction = rt.dependencies.historyIntentDispatcher.dispatch(
                            TrackerHistoryIntent.CommitTrunk(
                                batch = batch,
                                activeSessionStartMs = rt.currentActiveSessionStartMs(),
                            )
                        )
                        TrackerHistoryRenderMapper.toQueuedLocations(transaction.snapshot, TrackerMapViewModel.TRAIL_POINT_LIMIT)
                    } else {
                        val batch = enrichTrunkBatchForActiveSession(
                            TrackerHistorySourceAdapters.filteredServerTrunk(tracker),
                            trackerId = trackerId,
                        )
                        val transaction = rt.dependencies.historyIntentDispatcher.dispatch(
                            TrackerHistoryIntent.CommitTrunk(
                                batch = batch,
                                activeSessionStartMs = rt.currentActiveSessionStartMs(),
                            )
                        )
                        TrackerHistoryRenderMapper.toQueuedLocations(transaction.snapshot, TrackerMapViewModel.TRAIL_POINT_LIMIT)
                    }
                }
                TrackerMapServerTrailResult(
                    trailsByTracker = trails,
                    authoritativeTrackerIds = authoritativeIds,
                )
            }
            is RepositoryResult.Failure -> {
                val trackers = rt.dependencies.trackerManagementStateStore.trackers.value
                TrackerMapServerTrailResult(
                    trailsByTracker = normalizedIds.associateWith { trackerId ->
                        val window = TrackerMapHistoryUiSync.historyWindowForTracker(trackerId, trackers)
                        val queueTrail = loadQueueTrail(trackerId)
                        val batch = TrackerHistorySourceAdapters.degradedLocalOnlyTrunk(
                            trackerId = trackerId,
                            window = window,
                            queuedLocations = queueTrail,
                        )
                        val transaction = rt.dependencies.historyIntentDispatcher.dispatch(
                            TrackerHistoryIntent.CommitTrunk(
                                batch = batch,
                                activeSessionStartMs = rt.currentActiveSessionStartMs(),
                            )
                        )
                        TrackerHistoryRenderMapper.toQueuedLocations(transaction.snapshot, TrackerMapViewModel.TRAIL_POINT_LIMIT)
                    },
                    authoritativeTrackerIds = emptySet(),
                )
            }
        }
    }

    /**
     * Bounds a single reload geometry fetch to [MapGeometryReloadCircuitBreaker.NETWORK_TIMEOUT_MS]
     * — far tighter than the shared client's 30s connect/read/write legs — and consults/updates
     * [MapTrailReloadSubsystem.geometryReloadCircuitBreaker] so a run of consecutive failures (e.g. the
     * server is unreachable) stops attempting new network fetches for a cooldown window instead of
     * re-paying the same timeout on every reload trigger. A skipped or timed-out attempt is
     * reported as [AppError.Network], which every call site already treats as "fall back to the
     * local queue/degraded trunk" — no separate failure path needed.
     */
    private suspend fun <T : Any> fetchGeometryGuarded(
        trackerIds: Set<String>,
        loader: suspend () -> RepositoryResult<T>,
    ): RepositoryResult<T> {
        if (!geometryReloadCircuitBreaker.shouldAttempt()) {
            return RepositoryResult.Failure(AppError.Network)
        }
        val startMs = System.currentTimeMillis()
        val result = withTimeoutOrNull(MapGeometryReloadCircuitBreaker.NETWORK_TIMEOUT_MS) { loader() }
            ?: RepositoryResult.Failure(AppError.Network)
        val elapsedMs = System.currentTimeMillis() - startMs
        when (result) {
            is RepositoryResult.Success -> geometryReloadCircuitBreaker.recordSuccess()
            is RepositoryResult.Failure -> geometryReloadCircuitBreaker.recordFailure()
        }
        // Only diagnostically interesting -- worth a breadcrumb -- when it happens while a
        // recording session is active for one of the same trackers, since that's exactly the
        // "stalled local map on the recording device" failure mode this plan set out to catch.
        // A slow reload for a tracker nobody is currently recording is unremarkable network
        // noise and would otherwise just add log spam.
        if (elapsedMs >= StreamingConfig.reloadNetworkSlowDuringRecordingThresholdMs) {
            val recordedTrackerId = rt.stateHub.uiStateMutable.value.runtime.locallyRecordedTrackerId
            if (recordedTrackerId.isNotEmpty() && recordedTrackerId in trackerIds) {
                StreamingDiagnostics.logReloadNetworkSlowDuringRecording(trackerIds, elapsedMs)
            }
        }
        return result
    }

    /**
     * Seed `rt.stateHub.uiStateMutable.trail` from the local Room queue at ViewModel construction. Runs
     * concurrently with the rest of `init`; the goal is for this to land before the
     * Compose layer attaches its `rt.stateHub.uiStateMutable.collect` listener so the very first render
     * package the map sees already has a trail to fit the camera to.
     *
     * Race semantics: if any other code path populates the trail first (a server fetch,
     * a track point arriving, a reload coordinator preload), we leave it alone. If the
     * trail is still empty when we land, we populate it AND seed `displayedTrackerId`
     * from the persisted selection so downstream camera/render logic has a target.
     */
    internal suspend fun seedInitialTrailFromLocalQueue() {
        val context = rt.ports.application
        val selectedId = SelectedTrackerPrefs.selectedTrackerId(context).trim()
        if (selectedId.isEmpty()) return
        val queueTrail = loadQueueTrail(selectedId)
        if (queueTrail.isEmpty()) return
        val trackers = rt.dependencies.trackerManagementStateStore.trackers.value
        val window = TrackerMapHistoryUiSync.historyWindowForTracker(selectedId, trackers)
        val batch = TrackerHistorySourceAdapters.degradedLocalOnlyTrunk(
            trackerId = selectedId,
            window = window,
            queuedLocations = queueTrail,
        )
        // LOCK SCOPE: this cold-start seed races directly against the point consumer and any
        // in-flight reload commit -- both of which serialize their trail writes through
        // `trailCommitLock` -- so the dispatch-then-read-back-via-`applyHistoryTrailsToState`
        // sequence here must run under the same lock to avoid interleaving with a concurrent
        // commit of the same trail-bearing fields.
        rt.trailCommitLock.withCommitLock {
            rt.dependencies.historyIntentDispatcher.dispatch(
                TrackerHistoryIntent.CommitTrunk(
                    batch = batch,
                    activeSessionStartMs = null,
                ),
            )
            rt.stateHub.uiStateMutable.update { latest ->
                val displayedNow = latest.displayedTrackerId.trim()
                if (displayedNow.isNotEmpty() && displayedNow != selectedId) return@update latest
                val plan = rt.projectSession(latest)
                val trailsState = rt.display.applyHistoryTrailsToState(latest, plan)
                if (trailsState.trail.isEmpty() && trailsState.allQueueTrailsByTracker.isEmpty()) {
                    return@update latest
                }
                val displayedId = if (latest.displayedTrackerId.isBlank()) {
                    selectedId
                } else {
                    latest.displayedTrackerId
                }
                val displayedName = if (latest.displayedTrackerName.isBlank()) {
                    SelectedTrackerPrefs.selectedTrackerName(context)
                } else {
                    latest.displayedTrackerName
                }
                trailsState.copy(
                    displayedTrackerId = displayedId,
                    displayedTrackerName = displayedName,
                )
            }
        }
    }

    /**
     * Seed the single-tracker trail from the most recently available local source so the
     * map has SOMETHING to fit to before the (slow) server geometry fetch returns.
     *
     * Source priority:
     *  1. In-memory `TrackerManagementStateStore` cache. Populated by previous geometry
     *     fetches in this process; effectively always empty on a fresh launch because
     *     `GET /trackers/` returns metadata-only (no geometry).
     *  2. Local Room queue (`loadQueueTrail`). Persists across process death, so on every
     *     launch after the first recording session this provides recent fixes for the
     *     locally-recorded tracker without any network round-trip. The merge policy
     *     drops these once the server response arrives (queue rows are not tagged as
     *     live overlay), so they cleanly hand off without leaving stale data behind.
     *
     * Returning null means "no local data available, let the server fetch handle it."
     * The two-source pattern eliminates the visible 0,0 flash that occurred when the
     * cache was empty (every cold launch) and the trail stayed empty through the
     * geometry fetch window.
     */
    private suspend fun preloadedSingleTrackerTrailFromCacheOrNull(
        mode: TrackerMapDisplayMode,
        activeTrackerId: String,
        reloadId: Long,
    ): List<QueuedLocation>? {
        if (mode != TrackerMapDisplayMode.SINGLE_SESSION) return null
        val trackerId = activeTrackerId.trim()
        if (trackerId.isEmpty()) return null
        val cachedTracker = rt.dependencies.trackerManagementStateStore.trackers.value
            .firstOrNull { it.id == trackerId }
        val cachedGeometry = cachedTracker?.geometry?.coordinates.orEmpty()
        if (cachedGeometry.isNotEmpty() && cachedTracker != null) {
            val sessionStart = rt.currentActiveSessionStartMs()
            val batch = TrackerHistorySourceAdapters.filteredServerTrunk(cachedTracker)
            when (
                val prepared = TrackerHistoryActiveSessionPolicy.prepareTrunkForCommit(
                    batch = batch,
                    activeSessionStartMs = sessionStart,
                )
            ) {
                is TrackerHistoryTrunkPrepareResult.Reject -> {
                    GeoVaultCaptureLog.d(
                        TrackerMapViewModel.TAG,
                        "map_update cache_provenance_skip reloadId=$reloadId source=tracker_cache " +
                            "tracker=$trackerId reason=${prepared.reason} coords=${cachedGeometry.size} " +
                            "session=${sessionStart ?: -1}",
                    )
                }
                is TrackerHistoryTrunkPrepareResult.Commit -> {
                    val batch = enrichTrunkBatchForActiveSession(prepared.batch, trackerId = trackerId)
                    // LOCK SCOPE: this dispatch-then-immediate-read pair shares the history
                    // repository with the point consumer's and reload's own trunk commits (both
                    // guarded by `trailCommitLock`); serialize here too so this cache preload's
                    // commit can't interleave with -- and silently lose to, or clobber -- one of
                    // those.
                    val trail = rt.trailCommitLock.withCommitLock {
                        rt.dependencies.historyIntentDispatcher.dispatch(
                            TrackerHistoryIntent.CommitTrunk(
                                batch = batch,
                                activeSessionStartMs = sessionStart,
                            ),
                        )
                        TrackerHistoryRenderMapper.toQueuedLocations(
                            rt.dependencies.historyRepository.snapshotFor(TrackerHistoryKey(trackerId, batch.window)),
                            TrackerMapViewModel.TRAIL_POINT_LIMIT,
                        )
                    }
                    if (trail.isNotEmpty()) {
                        GeoVaultCaptureLog.i(
                            TrackerMapViewModel.TAG,
                            "map_update cache_provenance reloadId=$reloadId source=tracker_cache " +
                                "tracker=$trackerId coords=${cachedGeometry.size} materialized=${trail.size} " +
                                "clipped=${prepared.clipped}",
                        )
                        return trail
                    }
                }
            }
        }
        val queueTrail = loadQueueTrail(trackerId)
        if (queueTrail.isEmpty()) return null
        val window = TrackerMapHistoryUiSync.historyWindowForTracker(
            trackerId,
            rt.dependencies.trackerManagementStateStore.trackers.value,
        )
        val batch = TrackerHistorySourceAdapters.degradedLocalOnlyTrunk(
            trackerId = trackerId,
            window = window,
            queuedLocations = queueTrail,
        )
        val trail = rt.trailCommitLock.withCommitLock {
            rt.dependencies.historyIntentDispatcher.dispatch(
                TrackerHistoryIntent.CommitTrunk(
                    batch = batch,
                    activeSessionStartMs = rt.currentActiveSessionStartMs(),
                ),
            )
            TrackerHistoryRenderMapper.toQueuedLocations(
                rt.dependencies.historyRepository.snapshotFor(TrackerHistoryKey(trackerId, window)),
                TrackerMapViewModel.TRAIL_POINT_LIMIT,
            )
        }
        GeoVaultCaptureLog.i(
            TrackerMapViewModel.TAG,
            "map_update cache_provenance reloadId=$reloadId source=room_queue tracker=$trackerId " +
                "points=${queueTrail.trailSummary()}",
        )
        return trail
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
                val id = sessionPlan.displayedTrackerId.trim().ifBlank { state.runtime.selectedTrackerId.trim() }
                if (id.isEmpty()) emptySet() else setOf(id)
            }
        }
    }

    private fun trailSeedForState(state: TrackerMapUiState): String {
        val groupSelection = rt.resolveGroupModeSelection(state)
        val rosterIds = rt.visibleMapRosterTrackerIds()
        val plan = rt.projectSession(
            state = state,
            groupSelection = groupSelection,
            visibleRosterTrackerIds = rosterIds,
        )
        return TrackerMapReloadSeedPolicy.trailSeed(
            TrackerMapTrailSeedInput(
                mode = state.mode,
                runtimeRunning = state.runtime.localRecordingActive,
                activeTrackerId = plan.displayedTrackerId,
                rosterTrackerIds = rosterIds,
                groupSelection = groupSelection,
                renderMetadataSignature = state.renderMetadataSignature,
            )
        )
    }

    private fun enrichTrunkBatchForActiveSession(
        batch: TrackerHistorySourceBatch,
        trackerId: String,
    ): TrackerHistorySourceBatch {
        val state = rt.stateHub.uiStateMutable.value
        val normalizedId = trackerId.trim()
        val currentTrail = when (state.mode) {
            TrackerMapDisplayMode.SINGLE_SESSION -> state.trail
            TrackerMapDisplayMode.ALL_QUEUE,
            TrackerMapDisplayMode.GROUP_PLACEHOLDER -> state.allQueueTrailsByTracker[normalizedId].orEmpty()
        }
        return TrackerHistoryTrailPreservePolicy.mergeActiveSessionCoverageIntoTrunkBatch(
            batch = batch,
            currentTrail = currentTrail,
            activeSessionStartMs = rt.currentActiveSessionStartMs(),
            trailPointLimit = TrackerMapViewModel.TRAIL_POINT_LIMIT,
        )
    }
}
