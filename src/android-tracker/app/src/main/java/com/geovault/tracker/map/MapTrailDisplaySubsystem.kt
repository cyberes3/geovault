package com.geovault.tracker.map

import com.geovault.common.logging.CaptureLogThrottle
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.tracker.Tracker
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.presentation.*
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import com.geovault.tracker.ui.TrackerPointTimestamps
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.maplibre.android.geometry.LatLngBounds

/**
 * Owns turning current state into what actually gets drawn: projecting the effective session
 * (which trail(s), single vs. multi, live-head detection), deriving the immutable
 * [com.geovault.tracker.presentation.TrackerMapRenderPackage] the UI layer collects, and
 * resolving camera-fit bounds for that render. Every publish reads/writes trail state under
 * `rt.trailCommitLock` so it can never observe a reload or live-point commit mid-flight; see the
 * RENDER-SYNC UNDER LOCK note on [publishRenderPackage] below.
 */
internal class MapTrailDisplaySubsystem(private val rt: TrackerMapRuntime) {
    /**
     * RENDER-SYNC UNDER LOCK: reading history snapshots and building the draw package must happen
     * as one atomic step under `rt.trailCommitLock` — the same mutex [MapTrailReloadSubsystem]'s
     * reload commit and [MapStreamingSubsystem]'s live-point consumer already share. Draw reads
     * snapshots + unpublished overlay + remote heads; it does not write those trails back onto
     * `uiState`.
     */
    internal suspend fun publishRenderPackage() {
        val nowMs = System.currentTimeMillis()
        val effectiveSession = rt.trailCommitLock.withCommitLock {
            buildCurrentEffectiveSession(nowMs = nowMs)
        }
        val snapshot = effectiveSession.snapshot
        val nextRenderState = buildMapRenderState(snapshot)
        val nextBounds = trailBoundsOrNull(snapshot, nowMs)
        val nextSelectionLockPoint = rt.context.selectionLockPointOrNull(snapshot)
        val renderSignature =
            "mode=${snapshot.mode}|displayed=${snapshot.plan.displayedTrackerId}|single=${snapshot.singleTrail.size}|" +
                "multi=${snapshot.renderTrailsByTracker.mapSizes()}|liveHead=${effectiveSession.liveHead}|" +
                "bounds=${nextBounds.boundsSummary()}|selectionLock=${rt.stateHub.uiStateMutable.value.selectionLockTrackerId.trim()}|" +
                "historyKeys=${rt.dependencies.historyRepository.snapshots.value.size}"
        if (CaptureLogThrottle.shouldLogOnChange("map_draw_package", renderSignature)) {
            GeoVaultCaptureLog.d(
                TrackerMapViewModel.TAG,
                "map_draw_package mode=${snapshot.mode} displayed=${snapshot.plan.displayedTrackerId} " +
                    "selected=${snapshot.plan.selectedTrackerId} single=${snapshot.singleTrail.trailSummary()} " +
                    "multi=${snapshot.renderTrailsByTracker.mapSizes()} remote=${snapshot.acceptedRemoteLastPoints.keys.sorted()} " +
                    "history_snapshot_keys=${rt.dependencies.historyRepository.snapshots.value.size} " +
                    "liveHead=${effectiveSession.liveHead} bounds=${nextBounds.boundsSummary()} " +
                    "selectionLock=${rt.stateHub.uiStateMutable.value.selectionLockTrackerId.trim()} selectionPoint=$nextSelectionLockPoint",
            )
        }
        rt.stateHub.renderPackageMutable.update { current ->
            // RENDER-COALESCE: every rt.stateHub.uiStateMutable tick previously bumped `revision`, which made every
            // downstream collector (camera effects, polyline rerenders, marker refreshes) treat
            // every state change as a unique frame even when the rendered output was bit-identical.
            // Compare the structural fields and only mint a new revision when the visible scene
            // truly changes. Identity equality on `renderState` is fine because [MapRenderState] is
            // a data class produced from immutable inputs; pair it with bounds and selection-lock
            // coords for completeness.
            if (current.renderState == nextRenderState &&
                current.bounds == nextBounds &&
                current.selectionLockPoint == nextSelectionLockPoint &&
                current.liveHead == effectiveSession.liveHead
            ) {
                current
            } else {
                TrackerMapRenderPackage(
                    renderState = nextRenderState,
                    bounds = nextBounds,
                    selectionLockPoint = nextSelectionLockPoint,
                    liveHead = effectiveSession.liveHead,
                    revision = current.revision + 1L,
                )
            }
        }
        publishCameraDirective(
            state = snapshot.uiState,
            liveHead = effectiveSession.liveHead,
            bounds = nextBounds,
            selectionLockPoint = nextSelectionLockPoint,
        )
    }

    /**
     * CAMERA-DIRECTIVE: hands the precedence-aware resolution off to
     * [TrackerMapRuntime.cameraCoordinator], which owns id minting, dedup, and generation
     * stamping. This function's only job is projecting the current session into the resolver's
     * input shape and logging the decision.
     */
    internal fun refreshFollowLockCamera() {
        val snapshot = buildCurrentSessionSnapshot()
        val nextBounds = trailBoundsOrNull(snapshot, System.currentTimeMillis())
        publishCameraDirective(
            state = snapshot.uiState,
            liveHead = TrackerMapEffectiveSessionProjector.resolveLiveHead(snapshot),
            bounds = nextBounds,
            selectionLockPoint = rt.context.selectionLockPointOrNull(snapshot),
        )
    }

    private fun publishCameraDirective(
        state: TrackerMapUiState,
        liveHead: Pair<Double, Double>?,
        bounds: org.maplibre.android.geometry.LatLngBounds?,
        selectionLockPoint: Pair<Double, Double>?,
    ) {
        val followTarget = TrackerMapFollowLockTarget.resolve(
            followLockEnabled = state.followLockEnabled,
            puckLatitude = rt.cameraCoordinator.followPuckLatitude(),
            puckLongitude = rt.cameraCoordinator.followPuckLongitude(),
            liveHead = liveHead,
        )
        val input = TrackerMapCameraDirectiveInput(
            followLockEnabled = state.followLockEnabled,
            gpsCollecting = state.runtime.gpsCollecting,
            followTargetLat = followTarget?.first,
            followTargetLon = followTarget?.second,
            selectionLockEnabled = state.selectionLockTrackerId.trim().isNotEmpty(),
            selectionLockLat = selectionLockPoint?.first,
            selectionLockLon = selectionLockPoint?.second,
            liveActiveFitEnabled = state.liveActiveFitEnabled,
            bounds = bounds,
            userOwnsZoom = rt.cameraCoordinator.userOwnsZoom,
        )
        if (CaptureLogThrottle.shouldLogOnChange("vm_camera_resolve", input.toString())) {
            GeoVaultCaptureLog.d(
                TrackerMapViewModel.TAG,
                "map_update vm_camera_resolve mode=${state.mode} follow=${state.followLockEnabled} " +
                    "gpsCollecting=${state.runtime.gpsCollecting} followTarget=$followTarget " +
                    "selectionLock=${state.selectionLockTrackerId.trim()} selectionPoint=$selectionLockPoint " +
                    "liveFit=${state.liveActiveFitEnabled} bounds=${bounds.boundsSummary()}"
            )
        }
        rt.cameraCoordinator.resolveFromLockState(input)
    }

    private fun buildCurrentEffectiveSession(nowMs: Long = System.currentTimeMillis()): TrackerMapEffectiveSession {
        return buildEffectiveSessionForState(rt.stateHub.uiStateMutable.value, nowMs)
    }

    internal fun buildCurrentSessionSnapshot(nowMs: Long = System.currentTimeMillis()): TrackerMapSessionSnapshot {
        return buildCurrentEffectiveSession(nowMs).snapshot
    }

    internal fun buildSessionSnapshotForState(
        state: TrackerMapUiState,
        nowMs: Long = System.currentTimeMillis(),
    ): TrackerMapSessionSnapshot {
        return buildEffectiveSessionForState(state, nowMs).snapshot
    }

    internal fun acceptedRemoteTrackerIdsForCurrentSession(): Set<String> {
        return buildCurrentSessionSnapshot().plan.acceptedRemoteTrackerIds
    }

    private fun buildEffectiveSessionForState(
        state: TrackerMapUiState,
        nowMs: Long = System.currentTimeMillis(),
    ): TrackerMapEffectiveSession {
        val groupSelection = rt.resolveGroupModeSelection(state)
        val plan = rt.projectSession(
            state = state,
            groupSelection = groupSelection,
            visibleRosterTrackerIds = rt.visibleMapRosterTrackerIds(),
        )
        val trackers = rt.dependencies.trackerManagementStateStore.trackers.value
        TrackerMapHistoryUiSync.syncRuntimeHeadOverlay(
            runtime = state.runtime,
            plan = plan,
            snapshots = rt.dependencies.historyRepository.snapshots.value,
            trackers = trackers,
            dispatcher = rt.dependencies.historyIntentDispatcher,
            trailPointLimit = TrackerMapViewModel.TRAIL_POINT_LIMIT,
        )
        val snapshots = rt.dependencies.historyRepository.snapshots.value
        val visibleIds = visibleTrackerIdsForSessionPlan(state, plan)
        val unpublished = TrackerMapHistoryUiSync.unpublishedOverlaysByTracker(
            repository = rt.dependencies.historyRepository,
            trackerIds = TrackerMapHistoryUiSync.historyTrackerIdsForRender(state, plan, visibleIds),
            trackers = trackers,
        )
        val trails = TrackerMapHistoryUiSync.trailsFromSnapshots(
            state = state,
            plan = plan,
            snapshots = snapshots,
            trackers = trackers,
            trailPointLimit = TrackerMapViewModel.TRAIL_POINT_LIMIT,
            visibleTrackerIds = visibleIds,
            unpublishedOverlaysByTracker = unpublished,
        )
        val trailsState = state.copy(
            trail = trails.trail,
            allQueueTrailsByTracker = trails.allQueueTrailsByTracker,
        )
        return TrackerMapEffectiveSessionProjector.project(
            TrackerMapEffectiveSessionInput(
                state = trailsState,
                plan = plan,
                trailPointLimit = TrackerMapViewModel.TRAIL_POINT_LIMIT,
                visibleTrackerIds = visibleIds,
                nowMs = nowMs,
            )
        )
    }

    /**
     * MUTEX-GUARDED REPROJECT: must go through [rt.trailCommitLock] like every other
     * trail-mutating path ([publishRenderPackage], [MapTrailReloadSubsystem]'s reload commit,
     * the live-point consumer in [MapStreamingSubsystem]). This used to read
     * `rt.stateHub.uiStateMutable.value`, compute a plan, and blind-assign `rt.stateHub.uiStateMutable.value = ...`
     * with no lock at all -- a reload commit landing in between the read and the write would be
     * silently reverted by this function's now-stale write (last-writer-wins), exactly the class
     * of race the mutex exists to prevent. Recomputing against `latest` inside `update {}` (rather
     * than the `state`/`plan` snapshotted before acquiring the lock) additionally protects against
     * any non-mutex-guarded field changes that land while this suspends waiting for the lock.
     */
    internal fun reprojectTrailsFromRepository(reason: String) {
        if (!rt.context.isMapReady || !rt.context.isMapSurfaceVisible) return
        if (rt.dependencies.historyRepository.snapshots.value.isEmpty()) return
        rt.ports.viewModelScope.launch {
            rt.trailCommitLock.withCommitLock {
                val snapshots = rt.dependencies.historyRepository.snapshots.value
                if (snapshots.isEmpty()) return@withCommitLock
                GeoVaultCaptureLog.d(
                    TrackerMapViewModel.TAG,
                    "map_update vm_trail_reproject reason=$reason snapshot_keys=${snapshots.size}",
                )
                rt.stateHub.uiStateMutable.update { latest -> applyHistoryTrailsToState(latest, rt.projectSession(latest)) }
            }
        }
    }

    internal fun applyHistoryTrailsToState(
        state: TrackerMapUiState,
        plan: TrackerMapStreamingPlan,
    ): TrackerMapUiState {
        val trackers = rt.dependencies.trackerManagementStateStore.trackers.value
        val visibleIds = visibleTrackerIdsForSessionPlan(state, plan)
        TrackerMapHistoryUiSync.syncRuntimeHeadOverlay(
            runtime = state.runtime,
            plan = plan,
            snapshots = rt.dependencies.historyRepository.snapshots.value,
            trackers = trackers,
            dispatcher = rt.dependencies.historyIntentDispatcher,
            trailPointLimit = TrackerMapViewModel.TRAIL_POINT_LIMIT,
        )
        val unpublished = TrackerMapHistoryUiSync.unpublishedOverlaysByTracker(
            repository = rt.dependencies.historyRepository,
            trackerIds = TrackerMapHistoryUiSync.historyTrackerIdsForRender(state, plan, visibleIds),
            trackers = trackers,
        )
        val trails = TrackerMapHistoryUiSync.trailsFromSnapshots(
            state = state,
            plan = plan,
            snapshots = rt.dependencies.historyRepository.snapshots.value,
            trackers = trackers,
            trailPointLimit = TrackerMapViewModel.TRAIL_POINT_LIMIT,
            visibleTrackerIds = visibleIds,
            unpublishedOverlaysByTracker = unpublished,
        )
        return state.copy(
            trail = trails.trail,
            allQueueTrailsByTracker = trails.allQueueTrailsByTracker,
        )
    }

    private fun visibleTrackerIdsForSessionPlan(
        state: TrackerMapUiState,
        plan: TrackerMapStreamingPlan,
    ): Set<String>? {
        // ROSTER FILTER: SINGLE_SESSION passes null so the engine renders every trail it has
        // (the displayed-tracker logic is single-trail anyway and doesn't iterate the `tracks`
        // map). ALL_QUEUE passes `plan.visibleRosterTrackerIds`, which has already been run
        // through [HiddenMapItemsPolicy.visibleTrackerIdsForMap]. GROUP_PLACEHOLDER takes the
        // group's raw member list and intersects it with the hidden-filtered roster here — the
        // projector copies group members verbatim and would otherwise let a hidden group member
        // render. Passing an empty (but non-null) set is meaningful: "every group member is
        // hidden, render nothing", which the engine honors.
        return when (state.mode) {
            TrackerMapDisplayMode.GROUP_PLACEHOLDER -> HiddenMapItemsPolicy
                .visibleTrackerIdsForMap(
                    rosterTrackerIds = plan.groupTrackerIds,
                    mapVisibility = rt.dependencies.trackerManagementStateStore.mapVisibility.value,
                    trackers = rt.dependencies.trackerManagementStateStore.trackers.value,
                )
            TrackerMapDisplayMode.ALL_QUEUE -> plan.visibleRosterTrackerIds
            TrackerMapDisplayMode.SINGLE_SESSION -> null
        }
    }

    internal fun buildMapRenderState(): com.geovault.common.maps.render.MapRenderState {
        val snapshot = buildCurrentSessionSnapshot()
        return buildMapRenderState(snapshot)
    }

    private fun buildMapRenderState(
        snapshot: TrackerMapSessionSnapshot
    ): com.geovault.common.maps.render.MapRenderState {
        val s = snapshot.uiState
        val renderAllQueueTrailsByTracker = snapshot.renderTrailsByTracker
        val trackerColors = rt.dependencies.trackerManagementStateStore.trackers.value.associate { it.id to (it.color ?: "") }
        val trackerDisplayNames = rt.dependencies.trackerManagementStateStore.trackers.value.associate { it.id to it.name }
        val trackerRenderOrder = rt.dependencies.trackerManagementStateStore.trackers.value.map { it.id }
        val effectiveDisplayedId = TrackerMapDisplayIds.effectiveDisplayedTrackerId(s)
        val effectiveMapState = s.copy(
            trail = snapshot.singleTrail,
            allQueueTrailsByTracker = renderAllQueueTrailsByTracker,
            remoteLastPoints = snapshot.acceptedRemoteLastPoints,
        )
        val fallbackAccuracyByTrackerId = buildFallbackAccuracyByTrackerId(effectiveMapState, snapshot.plan)
        val visibleTrackerIds = resolveVisibleAccuracyTrackerIds(
            effectiveMapState,
            effectiveDisplayedId,
        )
        val allowAccuracyFallbackByTrackerId = TrackerAccuracyFallbackPolicy.resolveAllowedFallbackTrackerIds(
            TrackerAccuracyFallbackPolicyInput(
                mode = s.mode,
                runtimeRunning = s.runtime.localRecordingActive,
                selectedTrackerId = s.runtime.selectedTrackerId,
                displayedTrackerId = effectiveDisplayedId,
                visibleTrackerIds = visibleTrackerIds,
            )
        )
        return TrackerMapStateTransforms.buildRenderState(
            session = snapshot,
            cosmetics = TrackerMapRenderCosmetics(
                trackerColorById = trackerColors,
                trackerDisplayNameById = trackerDisplayNames,
                selectedMapTrackerId = TrackerMapViewModel.resolveRenderSelectedMapTrackerId(
                    isBottomCardVisible = s.isBottomCardVisible,
                    selectedMapTrackerId = s.selectedMapTracker?.trackerId
                ),
                trackerRenderOrder = trackerRenderOrder,
                defaultIconColorHex = GeoVaultColorTokens.Hex.Blue400,
            ),
            accuracy = TrackerMapAccuracyRenderModel(
                fallbackAccuracyByTrackerId = fallbackAccuracyByTrackerId,
                allowAccuracyFallbackByTrackerId = allowAccuracyFallbackByTrackerId,
            ),
        )
    }

    internal fun trailBoundsOrNull(): LatLngBounds? {
        val snapshot = buildCurrentSessionSnapshot()
        return trailBoundsOrNull(snapshot, System.currentTimeMillis())
    }

    private fun trailBoundsOrNull(
        snapshot: TrackerMapSessionSnapshot,
        nowMs: Long,
    ): LatLngBounds? {
        val s = snapshot.uiState
        if (s.mode == TrackerMapDisplayMode.ALL_QUEUE || s.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
            val sessionPlan = snapshot.plan
            val visibleTrackerIds = visibleTrackerIdsForSessionPlan(s, sessionPlan).orEmpty()
            val groupBoundsInput = TrackerMapGroupBoundsInput(
                visibleTrackerIds = visibleTrackerIds,
                liveActiveFitEnabled = s.liveActiveFitEnabled,
                fitOnlyActiveTrackers = rt.dependencies.trackerSettingsRepository.getSettings().groupModeFitOnlyActiveTrackers,
                trailsByTracker = snapshot.renderTrailsByTracker,
                remoteLastPoints = snapshot.acceptedRemoteLastPoints,
                acceptedRemoteTrackerIds = sessionPlan.acceptedRemoteTrackerIds,
                trackers = rt.dependencies.trackerManagementStateStore.trackers.value,
                nowMs = nowMs,
                runtime = snapshot.runtime,
            )
            // HOLD-ON-EMPTY-ACTIVE-ONLY: TrackerMapGroupBoundsResolution.Hold means "fit only
            // active trackers" is on but nobody currently qualifies -- falling back to the
            // all-tracker/single-point bounds below would silently override that intent with an
            // unrelated fit every time the roster goes quiet. Hold the camera (null -> None
            // directive, see TrackerMapCameraDirectivePolicy) instead until a tracker becomes
            // active again.
            return when (val resolution = TrackerMapGroupBoundsResolver.resolveOrHold(groupBoundsInput)) {
                is TrackerMapGroupBoundsResolution.Bounds -> resolution.bounds
                TrackerMapGroupBoundsResolution.Hold -> null
                TrackerMapGroupBoundsResolution.NoBounds ->
                    TrackerMapStateTransforms.trailBounds(snapshot.singleTrail)
                        ?: singlePointBoundsFromRuntime(s.runtime)
            }
        }
        val sessionPlan = snapshot.plan
        return TrackerMapStateTransforms.trailBounds(snapshot.singleTrail)
            ?: singlePointBoundsFromRuntime(s.runtime, sessionPlan)
    }

    private fun singlePointBoundsFromRuntime(
        runtime: TrackingRuntimeSnapshot,
        sessionPlan: TrackerMapStreamingPlan? = null,
    ): LatLngBounds? {
        if (sessionPlan != null &&
            sessionPlan.mode == TrackerMapDisplayMode.SINGLE_SESSION &&
            sessionPlan.selectedTrackerId.isNotEmpty() &&
            sessionPlan.displayedTrackerId != sessionPlan.selectedTrackerId
        ) {
            return null
        }
        val lat = runtime.lastTrackedLatitude ?: return null
        val lon = runtime.lastTrackedLongitude ?: return null
        return LatLngBounds.from(lat, lon, lat, lon)
    }

    private fun buildFallbackAccuracyByTrackerId(
        state: TrackerMapUiState,
        sessionPlan: TrackerMapStreamingPlan,
    ): Map<String, Float> {
        val fallbackByTrackerId = mutableMapOf<String, Float>()
        rt.dependencies.trackerManagementStateStore.trackers.value.forEach { tracker ->
            val trackerId = tracker.id.trim()
            if (trackerId.isEmpty()) return@forEach
            extractTrackerLatestAccuracyMeters(tracker)?.toFinitePositiveOrNull()?.let { accuracy ->
                fallbackByTrackerId[trackerId] = accuracy
            }
        }
        val selectedTrackerId = state.runtime.selectedTrackerId.trim()
        state.runtime.lastAccuracyMeters.toFinitePositiveOrNull()?.let { runtimeAccuracy ->
            if (selectedTrackerId.isNotEmpty() && selectedTrackerId in sessionPlan.localOverlayTrackerIds) {
                fallbackByTrackerId[selectedTrackerId] = runtimeAccuracy
            }
        }
        return fallbackByTrackerId
    }

    private fun resolveVisibleAccuracyTrackerIds(
        state: TrackerMapUiState,
        effectiveDisplayedId: String
    ): Set<String> {
        return buildSet {
            val displayedId = effectiveDisplayedId.trim()
            if (displayedId.isNotEmpty()) add(displayedId)
            state.allQueueTrailsByTracker.keys
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach(::add)
            state.remoteLastPoints.keys
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach(::add)
        }
    }

    private fun Float?.toFinitePositiveOrNull(): Float? {
        return this?.takeIf { it.isFinite() && it > 0f }
    }

    private fun extractTrackerLatestAccuracyMeters(tracker: Tracker): Float? {
        val accuracyRaw = tracker.point_params?.lastOrNull()?.get("acc") ?: return null
        return when (accuracyRaw) {
            is Number -> accuracyRaw.toFloat()
            is String -> accuracyRaw.toFloatOrNull()
            else -> null
        }
    }

}
