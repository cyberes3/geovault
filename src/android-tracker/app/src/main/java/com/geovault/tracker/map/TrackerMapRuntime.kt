package com.geovault.tracker.map

import com.geovault.tracker.SelectedTrackerManager
import com.geovault.tracker.presentation.TrackerMapCameraDirective
import com.geovault.tracker.presentation.TrackerMapGroupModeOption
import com.geovault.tracker.presentation.TrackerMapGroupModePolicy
import com.geovault.tracker.presentation.TrackerMapGroupModeSelection
import com.geovault.tracker.presentation.TrackerMapRenderPackage
import com.geovault.tracker.presentation.TrackerMapSessionIntent
import com.geovault.tracker.presentation.TrackerMapSessionProjector
import com.geovault.tracker.presentation.TrackerMapSessionRequestDeduper
import com.geovault.tracker.presentation.TrackerMapStreamingPlan
import com.geovault.tracker.presentation.TrackerMapTrailLoaderOps
import com.geovault.tracker.presentation.TrackerMapUiState
import com.geovault.tracker.presentation.HiddenMapItemsPolicy
import com.geovault.tracker.presentation.TrackerMapDisplayIds
import com.geovault.tracker.presentation.TrackerMapDisplayMode
import com.geovault.tracker.presentation.TrackerMapHistoryUiSync
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import kotlinx.coroutines.flow.StateFlow

/**
 * Composition root for the map feature: constructs and wires [dependencies], [stateHub], the
 * [cameraCoordinator]/[trailCommitLock] coordinators, and every per-concern subsystem instance,
 * then exposes the handful of stateless computations (`projectSession`,
 * `resolveGroupModeSelection`, `visibleMapRosterTrackerIds`, `resolveGroupModeOptions`,
 * `currentActiveSessionStartMs`, `activeSessionStartMsForRuntime`, `recomputeStaleRollingWindows`)
 * that are genuinely called from several subsystems with no single natural owner among them.
 *
 * This class holds only composition (dependencies, shared reactive state, coordinators,
 * subsystem instances) and stateless multi-consumer helpers. Do not add new mutable bookkeeping
 * fields here -- they belong on the subsystem that owns them. If a value is written from exactly
 * one subsystem, it lives as private state on that subsystem, with a narrow accessor method
 * exposed only if another subsystem genuinely needs to read it (see e.g.
 * [MapContextSubsystem.isMapReady], [MapTrailReloadSubsystem.invalidateLoadedSeed]).
 */
internal class TrackerMapRuntime(
    internal val ports: TrackerMapPorts,
) {
    internal val dependencies = TrackerMapDependencies(ports.application)

    internal val stateHub = TrackerMapStateHub()
    internal val uiState: StateFlow<TrackerMapUiState> = stateHub.uiState
    internal val renderPackage: StateFlow<TrackerMapRenderPackage> = stateHub.renderPackage
    internal val cameraCoordinator = TrackerMapCameraCoordinator()
    internal val cameraDirective: StateFlow<TrackerMapCameraDirective> = cameraCoordinator.directive
    internal val cameraGenerationFlow: StateFlow<Long> = cameraCoordinator.generationFlow
    internal fun cameraGeneration(): Long = cameraCoordinator.generation

    internal val trailCommitLock = TrailCommitCoordinator()
    internal val pendingReloadCameraFit = PendingReloadCameraFit()
    internal val sessionRequestDeduper = TrackerMapSessionRequestDeduper()
    internal val streamingPlanCache = TrackerMapStreamingPlanCache()
    internal lateinit var trailLoaderOps: TrackerMapTrailLoaderOps

    internal lateinit var context: MapContextSubsystem
    internal lateinit var display: MapTrailDisplaySubsystem
    internal lateinit var reload: MapTrailReloadSubsystem
    internal lateinit var streaming: MapStreamingSubsystem
    internal lateinit var streamRosterResolver: StreamRosterResolver
    internal lateinit var streamTargetReconciler: StreamTargetReconciler
    internal lateinit var trackPointReducer: TrackPointReducer

    internal fun start() {
        SelectedTrackerManager.syncRuntimeSelectedTracker(ports.application)
        wireSubsystems()
        trailLoaderOps = TrackerMapTrailLoaderOps(
            loadSingleServer = { trackerId, existingTrailMinTimeMs ->
                reload.loadSingleTrackerTrailFromServer(trackerId, existingTrailMinTimeMs)
            },
            loadMultiServer = { trackerIds, existingMultiMinTimes ->
                reload.loadTrailsForTrackerIds(trackerIds, existingMultiMinTimes)
            },
            loadQueue = { trackerId -> reload.loadQueueTrail(trackerId) },
        )
        streaming.startCollectors()
        streamRosterResolver.refreshStreamTargets()
    }

    private fun wireSubsystems() {
        context = MapContextSubsystem(this)
        display = MapTrailDisplaySubsystem(this)
        reload = MapTrailReloadSubsystem(this)
        streamRosterResolver = StreamRosterResolver(this)
        streamTargetReconciler = StreamTargetReconciler(this)
        trackPointReducer = TrackPointReducer(this)
        streaming = MapStreamingSubsystem(this)
    }

    internal fun trackerRosterForMapChip() = dependencies.trackerManagementStateStore.trackers.value

    internal fun onCleared() {
        streaming.close()
    }

    internal fun projectSession(
        state: TrackerMapUiState,
        groupSelection: TrackerMapGroupModeSelection = resolveGroupModeSelection(state),
        visibleRosterTrackerIds: Set<String> = visibleMapRosterTrackerIds(),
    ): TrackerMapStreamingPlan {
        return TrackerMapSessionProjector.project(
            TrackerMapSessionIntent(
                mode = state.mode,
                runtime = state.runtime,
                displayedTrackerId = state.displayedTrackerId,
                displayedTrackerName = state.displayedTrackerName,
                rosterTrackerIds = visibleRosterTrackerIds,
                groupSelection = groupSelection,
                activeStreamedTrackerIds = state.activeStreamedTrackerIds,
            ),
        )
    }

    internal fun resolveGroupModeSelection(state: TrackerMapUiState): TrackerMapGroupModeSelection {
        if (state.mode != TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
            return TrackerMapGroupModeSelection(groupId = null, trackerIds = emptySet())
        }
        val visibility = dependencies.trackerManagementStateStore.mapVisibility.value
        val hiddenGroupIds = visibility?.hidden_group_ids.orEmpty().toSet()
        val hiddenTrackIds = visibility?.hidden_track_ids.orEmpty().toSet()
        val hiddenOwnerTrackerIds = HiddenMapItemsPolicy.hiddenOwnerTrackerIds(dependencies.trackerManagementStateStore.trackers.value)
        val preferredTrackerId = TrackerMapDisplayIds.effectiveDisplayedTrackerId(state).ifBlank { state.runtime.selectedTrackerId }
        return TrackerMapGroupModePolicy.resolveSelection(
            groups = dependencies.trackerManagementStateStore.groups.value,
            hiddenGroupIds = hiddenGroupIds,
            hiddenTrackIds = hiddenTrackIds,
            hiddenOwnerTrackerIds = hiddenOwnerTrackerIds,
            rosterTrackerIds = dependencies.trackerManagementStateStore.trackers.value.mapTo(mutableSetOf()) { it.id.trim() },
            preferredGroupId = state.currentGroupId,
            preferredTrackerId = preferredTrackerId,
        )
    }

    internal fun visibleMapRosterTrackerIds(): Set<String> {
        val trackers = dependencies.trackerManagementStateStore.trackers.value
        return HiddenMapItemsPolicy.visibleTrackerIdsForMap(
            rosterTrackerIds = trackers.map { it.id },
            mapVisibility = dependencies.trackerManagementStateStore.mapVisibility.value,
            trackers = trackers,
        )
    }

    internal fun resolveGroupModeOptions(): List<TrackerMapGroupModeOption> {
        val visibility = dependencies.trackerManagementStateStore.mapVisibility.value
        val hiddenGroupIds = visibility?.hidden_group_ids.orEmpty().toSet()
        val hiddenTrackIds = visibility?.hidden_track_ids.orEmpty().toSet()
        val hiddenOwnerTrackerIds = HiddenMapItemsPolicy.hiddenOwnerTrackerIds(dependencies.trackerManagementStateStore.trackers.value)
        return TrackerMapGroupModePolicy.resolveEligibleGroups(
            groups = dependencies.trackerManagementStateStore.groups.value,
            hiddenGroupIds = hiddenGroupIds,
            hiddenTrackIds = hiddenTrackIds,
            hiddenOwnerTrackerIds = hiddenOwnerTrackerIds,
            rosterTrackerIds = dependencies.trackerManagementStateStore.trackers.value.mapTo(mutableSetOf()) { it.id.trim() },
        )
    }

    internal fun currentActiveSessionStartMs(): Long? {
        return activeSessionStartMsForRuntime(stateHub.uiStateMutable.value.runtime)
    }

    internal fun activeSessionStartMsForRuntime(runtime: TrackingRuntimeSnapshot): Long? {
        return TrackerMapHistoryUiSync.activeSessionStartMsForTracker(
            runtime = runtime,
            trackerId = runtime.locallyRecordedTrackerId,
        )
    }

    internal fun activeSessionStartMsForTracker(trackerId: String): Long? {
        return TrackerMapHistoryUiSync.activeSessionStartMsForTracker(
            runtime = stateHub.uiStateMutable.value.runtime,
            trackerId = trackerId,
        )
    }

    /**
     * IDLE-ROLLING-WINDOW STALENESS: see [TrackerHistoryRepository.recomputeStaleRollingWindows].
     * Called both periodically (from [MapStreamingSubsystem.startCollectors]'s ticker) and on
     * resume ([MapContextSubsystem.onHostResumed]) so a "last N hours"-style filter re-excludes
     * points that aged out of the window while the tracker was idle, not only while new points
     * are actively arriving.
     */
    internal fun recomputeStaleRollingWindows(): Boolean {
        val locallyRecordedTrackerId = stateHub.uiStateMutable.value.runtime.locallyRecordedTrackerId.trim()
        val activeSessionStartMs = currentActiveSessionStartMs()
        val changedKeys = dependencies.historyRepository.recomputeStaleRollingWindows(
            activeSessionStartMsFor = { trackerId ->
                if (trackerId == locallyRecordedTrackerId) activeSessionStartMs else null
            },
        )
        return changedKeys.isNotEmpty()
    }
}
