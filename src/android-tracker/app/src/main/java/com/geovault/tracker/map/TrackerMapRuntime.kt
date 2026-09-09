package com.geovault.tracker.map

import com.geovault.tracker.data.CatalogState
import com.geovault.tracker.presentation.TrackerMapCameraDirective
import com.geovault.tracker.streaming.StreamingOwner
import com.geovault.tracker.presentation.TrackerMapGroupModeOption
import com.geovault.tracker.presentation.TrackerMapGroupModeSelection
import com.geovault.tracker.presentation.TrackerMapRenderPackage
import com.geovault.tracker.presentation.TrackerMapSessionIntent
import com.geovault.tracker.presentation.TrackerMapStreamingPlan
import com.geovault.tracker.presentation.TrackerMapUiState
import com.geovault.tracker.presentation.HiddenMapItemsPolicy
import com.geovault.tracker.presentation.TrackerMapDisplayIds
import com.geovault.tracker.presentation.TrackerMapDisplayMode
import com.geovault.tracker.positioning.TrackingRuntimeSnapshot
import com.geovault.tracker.runtime.TrackerRuntimeStore
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Map composition factory: shared dependencies, reactive state, and the three engines.
 * Session, trail, and render behavior live on the engines.
 */
internal class TrackerMapRuntime(
    internal val ports: TrackerMapPorts,
) {
    internal val dependencies = TrackerMapDependencies(ports.application)

    internal val stateHub = TrackerMapStateHub()
    internal val uiState: StateFlow<TrackerMapUiState> = stateHub.uiState
    private val trailCommitMutex = Mutex()
    internal val isTrailCommitLocked: Boolean get() = trailCommitMutex.isLocked
    internal suspend fun <T> withTrailCommit(block: suspend () -> T): T =
        trailCommitMutex.withLock { block() }

    internal val sessionEngine = MapSessionEngine()
    internal val trailEngine = MapTrailEngine()
    internal val renderEngine = MapRenderEngine()
    internal val renderPackage: StateFlow<TrackerMapRenderPackage> = renderEngine.renderPackage
    internal val cameraDirective: StateFlow<TrackerMapCameraDirective> = renderEngine.cameraDirective
    internal val cameraGenerationFlow: StateFlow<Long> = renderEngine.cameraGenerationFlow
    internal fun cameraGeneration(): Long = renderEngine.cameraGeneration

    internal fun start() {
        trailEngine.start(this)
        renderEngine.start(this)
        sessionEngine.start(this)
    }

    internal fun catalog(): CatalogState = dependencies.catalogStateStore.state.value

    internal fun catalogSelectedTrackerId(): String = catalog().selectedTrackerId.trim()

    internal fun catalogSelectedTrackerName(): String {
        val id = catalogSelectedTrackerId()
        if (id.isEmpty()) return ""
        return catalog().trackers.firstOrNull { it.id.trim() == id }?.name?.trim().orEmpty()
    }

    internal fun recording(): TrackingRuntimeSnapshot = TrackerRuntimeStore.value.recording

    internal fun displayedTrackerId(state: TrackerMapUiState = stateHub.uiStateMutable.value): String {
        return TrackerMapDisplayIds.effectiveDisplayedTrackerId(state, catalogSelectedTrackerId())
    }

    internal fun trackerRosterForMapChip() = catalog().trackers

    internal fun onCleared() {
        sessionEngine.close()
        dependencies.liveStreamSubscriptionRepository.setLease(StreamingOwner.MAP, null)
    }

    internal fun projectSession(
        state: TrackerMapUiState,
        groupSelection: TrackerMapGroupModeSelection = resolveGroupModeSelection(state),
        visibleRosterTrackerIds: Set<String> = visibleMapRosterTrackerIds(),
    ): TrackerMapStreamingPlan {
        return MapSessionEngine.project(
            TrackerMapSessionIntent(
                mode = state.mode,
                runtime = recording(),
                selectedTrackerId = catalogSelectedTrackerId(),
                selectedTrackerName = catalogSelectedTrackerName(),
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
        val catalog = catalog()
        val visibility = catalog.mapVisibility
        val hiddenGroupIds = visibility?.hidden_group_ids.orEmpty().toSet()
        val hiddenTrackIds = visibility?.hidden_track_ids.orEmpty().toSet()
        val hiddenOwnerTrackerIds = HiddenMapItemsPolicy.hiddenOwnerTrackerIds(catalog.trackers)
        val preferredTrackerId = TrackerMapDisplayIds.effectiveDisplayedTrackerId(
            state,
            catalogSelectedTrackerId(),
        )
        return MapSessionEngine.resolveGroupSelection(
            groups = catalog.groups,
            hiddenGroupIds = hiddenGroupIds,
            hiddenTrackIds = hiddenTrackIds,
            hiddenOwnerTrackerIds = hiddenOwnerTrackerIds,
            rosterTrackerIds = catalog.trackers.mapTo(mutableSetOf()) { it.id.trim() },
            preferredGroupId = state.currentGroupId,
            preferredTrackerId = preferredTrackerId,
        )
    }

    internal fun visibleMapRosterTrackerIds(): Set<String> {
        val catalog = catalog()
        return HiddenMapItemsPolicy.visibleTrackerIdsForMap(
            rosterTrackerIds = catalog.trackers.map { it.id },
            mapVisibility = catalog.mapVisibility,
            trackers = catalog.trackers,
        )
    }

    internal fun resolveGroupModeOptions(): List<TrackerMapGroupModeOption> {
        val catalog = catalog()
        val visibility = catalog.mapVisibility
        val hiddenGroupIds = visibility?.hidden_group_ids.orEmpty().toSet()
        val hiddenTrackIds = visibility?.hidden_track_ids.orEmpty().toSet()
        val hiddenOwnerTrackerIds = HiddenMapItemsPolicy.hiddenOwnerTrackerIds(catalog.trackers)
        return MapSessionEngine.resolveEligibleGroups(
            groups = catalog.groups,
            hiddenGroupIds = hiddenGroupIds,
            hiddenTrackIds = hiddenTrackIds,
            hiddenOwnerTrackerIds = hiddenOwnerTrackerIds,
            rosterTrackerIds = catalog.trackers.mapTo(mutableSetOf()) { it.id.trim() },
        )
    }

    internal fun currentActiveSessionStartMs(): Long? {
        return activeSessionStartMsForRuntime(recording())
    }

    internal fun activeSessionStartMsForRuntime(runtime: TrackingRuntimeSnapshot): Long? {
        return MapTrailEngine.activeSessionStartMsForTracker(
            runtime = runtime,
            trackerId = runtime.locallyRecordedTrackerId,
        )
    }

    internal fun activeSessionStartMsForTracker(trackerId: String): Long? {
        return MapTrailEngine.activeSessionStartMsForTracker(
            runtime = recording(),
            trackerId = trackerId,
        )
    }

    /**
     * IDLE-ROLLING-WINDOW STALENESS: see [com.geovault.tracker.history.TrackerHistoryRepository.recomputeStaleRollingWindows].
     * Called both periodically (from [MapSessionEngine]'s ticker) and on resume so a
     * "last N hours"-style filter re-excludes points that aged out of the window while the
     * tracker was idle, not only while new points are actively arriving.
     */
    internal fun recomputeStaleRollingWindows(): Boolean {
        val locallyRecordedTrackerId = recording().locallyRecordedTrackerId.trim()
        val activeSessionStartMs = currentActiveSessionStartMs()
        val changedKeys = dependencies.historyRepository.recomputeStaleRollingWindows(
            activeSessionStartMsFor = { trackerId ->
                if (trackerId == locallyRecordedTrackerId) activeSessionStartMs else null
            },
        )
        return changedKeys.isNotEmpty()
    }
}
