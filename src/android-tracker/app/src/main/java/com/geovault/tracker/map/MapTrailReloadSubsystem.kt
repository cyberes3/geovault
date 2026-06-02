package com.geovault.tracker.map

import android.app.Application
import com.geovault.common.logging.CaptureLogThrottle
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.SelectedTrackerPrefs
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.history.*
import com.geovault.tracker.presentation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock

internal class MapTrailReloadSubsystem(private val rt: TrackerMapRuntime) {
    internal suspend fun reloadTrailFromDatabase(reason: TrackerMapTrailReloadReason) {
        rt.trailReloadMutex.withLock {
            reloadTrailFromDatabaseLocked(reason)
        }
    }

    internal suspend fun reloadTrailFromDatabaseLocked(reason: TrackerMapTrailReloadReason) {
        val state = rt.uiStateMutable.value
        val reloadId = rt.nextTrailReloadId++
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
            force = reason.allowServerHistoryFetch,
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
            return
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
                ?.let { rt.historyRepository.lastTrunkFetchedAtMs(it) }
            val refreshDecision = if (
                staleRosterIds.size > 1 &&
                (refreshCause == TrackerHistoryRefreshCause.Resume ||
                    refreshCause == TrackerHistoryRefreshCause.PeriodicRecording)
            ) {
                val anyStale = staleRosterIds.any { trackerId ->
                    val last = rt.historyRepository.lastTrunkFetchedAtMs(trackerId)
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
                return
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
            reconcileLocalQueueOverlayForSkippedReload(
                reason = reason,
                plan = sessionPlan.trailReloadPlan,
            )
            val skipSignature = "reason=$reason|source=${sessionPlan.trailReloadPlan.source}"
            if (CaptureLogThrottle.shouldLogOnChange("vm_reload_skip_source", skipSignature)) {
                GeoVaultCaptureLog.d(
                    TrackerMapViewModel.TAG,
                    "map_update vm_reload_skip_source reloadId=$reloadId reason=$reason source=${sessionPlan.trailReloadPlan.source} " +
                        "displayed=${sessionPlan.displayedTrackerId} trail=${state.trail.size}"
                )
            }
            return
        }
        // RE-FIT AFTER FETCH: every reload that legitimately hit the server can move state.trail
        // arbitrarily far from whatever the camera is currently framing (process death + resume,
        // launch with a stale runtime GPS coord, switching to a different selected tracker, etc).
        // Without this flag the fit-after-reload path only triggers from explicit map context
        // change handlers — leaving the camera frozen on stale bounds even though the trail data
        // it was sized to is no longer in state. Treat any server-fetching reload as the caller
        // having implicitly asked the camera to re-fit when the new data lands.
        if (reason.allowServerHistoryFetch) {
            rt.pendingFitAfterReload = true
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
        if (!reason.allowServerHistoryFetch && rt.lastTrailLoadSeed == seed) {
            GeoVaultCaptureLog.v(TrackerMapViewModel.TAG, "map_update vm_reload_seed_skip reason=$reason seed=$seed")
            return
        }
        rt.lastTrailLoadSeed = seed
        val planSourceState = rt.uiStateMutable.value
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
        // SINGLE_SERVER + LOCAL OVERLAY: when the displayed tracker is the locally-recorded one,
        // server geometry alone can lag the live recording (uploads are async). The loader pairs
        // the server fetch with the local DB queue (returned in queueOverlaysByTracker) so the
        // merge has authoritative recent fixes to splice on top of server history.
        // MULTI_SERVER mirrors this: server geometry is loaded for every group/roster member and
        // returned untouched in serverTrails; the locally-recorded tracker's queue rows arrive in
        // queueOverlaysByTracker and are spliced as live-overlay candidates by the merge. They
        // never replace the server entry, so the multi view always retains real history for every
        // member — including the recording user.
        val loaded = TrackerMapTrailLoader.load(
            plan = plan,
            existingTrailMinTimeMs = existingTrailMinTimeMs,
            existingMultiMinTimes = existingMultiMinTimes,
            ops = rt.trailLoaderOps,
        )
        GeoVaultCaptureLog.i(
            TrackerMapViewModel.TAG,
            "map_update vm_reload_loaded reloadId=$reloadId reason=$reason source=${plan.source} " +
                "single=${loaded.singleTrailSeed.trailSummary()} server=${loaded.serverTrails.mapSizes()} " +
                "queueOverlays=${loaded.queueOverlaysByTracker.mapSizes()} authoritative=${loaded.authoritativeServerTrackerIds.sorted()}"
        )
        val trackers = rt.trackerManagementStateStore.trackers.value
        TrackerMapHistoryUiSync.commitQueueOverlays(
            queueOverlaysByTracker = loaded.queueOverlaysByTracker,
            trackers = trackers,
            dispatcher = rt.historyIntentDispatcher,
            activeSessionStartMs = rt.currentActiveSessionStartMs(),
        )
        var mergeCommitted: MergedTrailResult? = null
        rt.uiStateMutable.update { latest ->
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
            val activeSnapshot = rt.historyRepository.snapshotFor(
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
        val finalMerge = mergeCommitted ?: run {
            rt.lastTrailLoadSeed = null
            return
        }
        if (rt.pendingFitAfterReload &&
            (finalMerge.trail.isNotEmpty() || finalMerge.multiTrails.isNotEmpty())
        ) {
            rt.pendingFitAfterReload = false
            // INSTANT after server-fetching reload: the InitialFit directive (or a prior
            // user-driven fit) has already framed the camera on the locally-preloaded
            // bounds. The server response typically nudges those bounds by a small delta;
            // animating that delta produces a visible jolt at first map open. moveCamera
            // snaps to the final framing in one frame, which is the right semantics for a
            // re-fit the user did not initiate.
            rt.context.requestFitTrail(TrackerMapFitTrailMode.Instant)
        }
    }

    internal data class MergedTrailResult(
        val trail: List<QueuedLocation>,
        val multiTrails: Map<String, List<QueuedLocation>>,
    )

    internal fun requestRuntimeTrailReload(reason: TrackerMapTrailReloadReason) {
        if (rt.runtimeTrailReloadJob?.isActive == true) {
            rt.runtimeTrailReloadPendingReason = rt.runtimeTrailReloadPendingReason.mergedWith(reason)
            return
        }
        rt.runtimeTrailReloadJob = rt.ports.viewModelScope.launch {
            var nextReason: TrackerMapTrailReloadReason? = reason
            while (nextReason != null) {
                val current = nextReason
                rt.runtimeTrailReloadPendingReason = null
                reloadTrailFromDatabase(current)
                nextReason = rt.runtimeTrailReloadPendingReason
            }
        }
    }

    internal suspend fun reconcileLocalQueueOverlayForSkippedReload(
        reason: TrackerMapTrailReloadReason,
        plan: TrackerMapTrailReloadPlan,
    ) {
        val overlayTrackerId = plan.overlayTrackerId?.trim().orEmpty()
        if (overlayTrackerId.isEmpty()) return
        if (!rt.uiStateMutable.value.runtime.localRecordingActive) return

        val initialState = rt.uiStateMutable.value
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
            trackers = rt.trackerManagementStateStore.trackers.value,
            dispatcher = rt.historyIntentDispatcher,
            activeSessionStartMs = rt.currentActiveSessionStartMs(),
        )
        var committed: MergedTrailResult? = null
        rt.uiStateMutable.update { latest ->
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
            committed = MergedTrailResult(trailsState.trail, trailsState.allQueueTrailsByTracker)
            GeoVaultCaptureLog.i(
                TrackerMapViewModel.TAG,
                "map_update vm_local_overlay_commit reason=$reason source=${latestPlan.trailReloadPlan.source} overlay=$overlayTrackerId " +
                    "queue=${queueOverlay.trailSummary()} trail=${trailsState.trail.trailSummary()} " +
                    "multi=${trailsState.allQueueTrailsByTracker.mapSizes()}",
            )
            trailsState
        }

        val finalMerge = committed ?: return
        if (rt.pendingFitAfterReload &&
            (finalMerge.trail.isNotEmpty() || finalMerge.multiTrails.isNotEmpty())
        ) {
            rt.pendingFitAfterReload = false
            rt.context.requestFitTrail(TrackerMapFitTrailMode.Instant)
        }
    }

    internal fun setOfNotBlank(value: String?): Set<String> {
        val normalized = value?.trim().orEmpty()
        return normalized.takeIf { it.isNotEmpty() }?.let(::setOf).orEmpty()
    }

    internal fun TrackerMapTrailReloadReason.allowsSource(source: TrackerMapTrailSource): Boolean {
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
            rt.dao.getRecentChronologicalForTracker(normalizedTrackerId, TrackerMapViewModel.QUEUE_TRAIL_FETCH_LIMIT)
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
                rt.geometryLoadingTracker.track { rt.trackerManagementRepository.loadTrackerGeometry(normalizedId) }
            }
        ) {
            is RepositoryResult.Success -> {
                val batch = enrichTrunkBatchForActiveSession(
                    TrackerHistorySourceAdapters.filteredServerTrunk(geometryResult.data),
                    trackerId = normalizedId,
                )
                val transaction = rt.historyIntentDispatcher.dispatch(
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
                val trackers = rt.trackerManagementStateStore.trackers.value
                val window = TrackerMapHistoryUiSync.historyWindowForTracker(normalizedId, trackers)
                val queueTrail = loadQueueTrail(normalizedId)
                val batch = TrackerHistorySourceAdapters.degradedLocalOnlyTrunk(
                    trackerId = normalizedId,
                    window = window,
                    queuedLocations = queueTrail,
                )
                val transaction = rt.historyIntentDispatcher.dispatch(
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
                rt.geometryLoadingTracker.track { rt.trackerManagementRepository.loadTrackersGeometry(normalizedIds) }
            }
        ) {
            is RepositoryResult.Success -> {
                val trackersById = result.data.associateBy { it.id.trim() }
                val authoritativeIds = trackersById.keys.intersect(normalizedIds.toSet())
                val trails = normalizedIds.associateWith { trackerId ->
                    val tracker = trackersById[trackerId]
                    if (tracker == null) {
                        val trackers = rt.trackerManagementStateStore.trackers.value
                        val window = TrackerMapHistoryUiSync.historyWindowForTracker(trackerId, trackers)
                        val queueTrail = loadQueueTrail(trackerId)
                        val batch = TrackerHistorySourceAdapters.degradedLocalOnlyTrunk(
                            trackerId = trackerId,
                            window = window,
                            queuedLocations = queueTrail,
                        )
                        val transaction = rt.historyIntentDispatcher.dispatch(
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
                        val transaction = rt.historyIntentDispatcher.dispatch(
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
                val trackers = rt.trackerManagementStateStore.trackers.value
                TrackerMapServerTrailResult(
                    trailsByTracker = normalizedIds.associateWith { trackerId ->
                        val window = TrackerMapHistoryUiSync.historyWindowForTracker(trackerId, trackers)
                        val queueTrail = loadQueueTrail(trackerId)
                        val batch = TrackerHistorySourceAdapters.degradedLocalOnlyTrunk(
                            trackerId = trackerId,
                            window = window,
                            queuedLocations = queueTrail,
                        )
                        val transaction = rt.historyIntentDispatcher.dispatch(
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
     * Seed `rt.uiStateMutable.trail` from the local Room queue at ViewModel construction. Runs
     * concurrently with the rest of `init`; the goal is for this to land before the
     * Compose layer attaches its `rt.uiStateMutable.collect` listener so the very first render
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
        val trackers = rt.trackerManagementStateStore.trackers.value
        val window = TrackerMapHistoryUiSync.historyWindowForTracker(selectedId, trackers)
        val batch = TrackerHistorySourceAdapters.degradedLocalOnlyTrunk(
            trackerId = selectedId,
            window = window,
            queuedLocations = queueTrail,
        )
        rt.historyIntentDispatcher.dispatch(
            TrackerHistoryIntent.CommitTrunk(
                batch = batch,
                activeSessionStartMs = null,
            ),
        )
        rt.uiStateMutable.update { latest ->
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
    internal suspend fun preloadedSingleTrackerTrailFromCacheOrNull(
        mode: TrackerMapDisplayMode,
        activeTrackerId: String,
        reloadId: Long,
    ): List<QueuedLocation>? {
        if (mode != TrackerMapDisplayMode.SINGLE_SESSION) return null
        val trackerId = activeTrackerId.trim()
        if (trackerId.isEmpty()) return null
        val cachedTracker = rt.trackerManagementStateStore.trackers.value
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
                    rt.historyIntentDispatcher.dispatch(
                        TrackerHistoryIntent.CommitTrunk(
                            batch = batch,
                            activeSessionStartMs = sessionStart,
                        ),
                    )
                    val trail = TrackerHistoryRenderMapper.toQueuedLocations(
                        rt.historyRepository.snapshotFor(TrackerHistoryKey(trackerId, batch.window)),
                        TrackerMapViewModel.TRAIL_POINT_LIMIT,
                    )
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
            rt.trackerManagementStateStore.trackers.value,
        )
        val batch = TrackerHistorySourceAdapters.degradedLocalOnlyTrunk(
            trackerId = trackerId,
            window = window,
            queuedLocations = queueTrail,
        )
        rt.historyIntentDispatcher.dispatch(
            TrackerHistoryIntent.CommitTrunk(
                batch = batch,
                activeSessionStartMs = rt.currentActiveSessionStartMs(),
            ),
        )
        GeoVaultCaptureLog.i(
            TrackerMapViewModel.TAG,
            "map_update cache_provenance reloadId=$reloadId source=room_queue tracker=$trackerId " +
                "points=${queueTrail.trailSummary()}",
        )
        return TrackerHistoryRenderMapper.toQueuedLocations(
            rt.historyRepository.snapshotFor(TrackerHistoryKey(trackerId, window)),
            TrackerMapViewModel.TRAIL_POINT_LIMIT,
        )
    }

internal fun rosterTrackerIdsForTrunkStaleCheck(
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
        val state = rt.uiStateMutable.value
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
