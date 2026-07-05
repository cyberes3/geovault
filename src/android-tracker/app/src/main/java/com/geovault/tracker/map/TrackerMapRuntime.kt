package com.geovault.tracker.map

import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.SelectedTrackerManager
import com.geovault.tracker.db.AppDatabase
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.data.TrackerManagementRepository
import com.geovault.tracker.data.TrackerManagementStateStore
import com.geovault.tracker.history.TrackerHistoryIntentDispatcher
import com.geovault.tracker.history.TrackerHistoryKey
import com.geovault.tracker.history.TrackerHistoryRepository
import com.geovault.tracker.history.TrackerHistorySessionBoundary
import com.geovault.tracker.history.TrackerHistoryWindowResolver
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.presentation.LiveTrackStreamingReconciler
import com.geovault.tracker.presentation.MapGeometryReloadCircuitBreaker
import com.geovault.tracker.presentation.TrackerMapCameraDirective
import com.geovault.tracker.presentation.TrackerMapCameraDirectivePolicy
import com.geovault.tracker.presentation.TrackerMapFilterChangeReactor
import com.geovault.tracker.presentation.TrackerMapGeometryLoadingTracker
import com.geovault.tracker.presentation.TrackerMapGroupModeOption
import com.geovault.tracker.presentation.TrackerMapGroupModePolicy
import com.geovault.tracker.presentation.TrackerMapGroupModeSelection
import com.geovault.tracker.presentation.TrackerMapReopenOrchestrator
import com.geovault.tracker.presentation.TrackerMapRenderPackage
import com.geovault.tracker.presentation.TrackerMapRuntimeResyncPolicy
import com.geovault.tracker.presentation.TrackerMapSessionIntent
import com.geovault.tracker.presentation.TrackerMapSessionProjector
import com.geovault.tracker.presentation.TrackerMapSessionRequestDeduper
import com.geovault.tracker.presentation.TrackerMapStreamingPlan
import com.geovault.tracker.presentation.TrackerMapTrailLoaderOps
import com.geovault.tracker.presentation.TrackerMapTrailReloadReason
import com.geovault.tracker.presentation.TrackerMapUiState
import com.geovault.tracker.presentation.TrackerMapViewModel
import com.geovault.tracker.presentation.HiddenMapItemsPolicy
import com.geovault.tracker.presentation.TrackerMapDisplayIds
import com.geovault.tracker.presentation.TrackerMapDisplayMode
import com.geovault.tracker.presentation.TrackerMapFitTrailMode
import com.geovault.tracker.settings.TrackerSettingsRepository
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import com.geovault.tracker.streaming.LiveStreamSubscriptionRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex

/**
 * Facade wiring map subsystems; holds shared flows and reload/streaming state.
 */
internal class TrackerMapRuntime(
    val ports: TrackerMapPorts,
) {
    internal val appContext = ports.application.applicationContext
    internal val liveStreamSubscriptionRepository: LiveStreamSubscriptionRepository =
        TrackerAppServices.from(ports.application).liveStreamSubscriptionRepository()
    internal val streamingReconciler = LiveTrackStreamingReconciler(liveStreamSubscriptionRepository)
    internal val dao = AppDatabase.getDatabase(ports.application).locationDao()
    internal val trackerManagementRepository: TrackerManagementRepository =
        TrackerAppServices.from(ports.application).trackerManagementRepository()
    internal val trackerManagementStateStore: TrackerManagementStateStore =
        TrackerAppServices.from(ports.application).trackerManagementStateStore()
    internal val trackerSettingsRepository: TrackerSettingsRepository =
        TrackerAppServices.from(ports.application).trackerSettingsRepository()

    internal val uiStateMutable = MutableStateFlow(TrackerMapUiState())
    val uiState: StateFlow<TrackerMapUiState> = uiStateMutable.asStateFlow()
    internal val renderPackageMutable = MutableStateFlow(TrackerMapRenderPackage())
    val renderPackage: StateFlow<TrackerMapRenderPackage> = renderPackageMutable.asStateFlow()
    internal val cameraDirectiveMutable = MutableStateFlow<TrackerMapCameraDirective>(TrackerMapCameraDirective.None())
    val cameraDirective: StateFlow<TrackerMapCameraDirective> = cameraDirectiveMutable.asStateFlow()
    internal var lastCameraResolution: TrackerMapCameraDirectivePolicy.Resolution =
        TrackerMapCameraDirectivePolicy.Resolution.None
    internal var nextCameraDirectiveId: Long = 1L

    internal val fitTrailSignal = Channel<TrackerMapFitTrailMode>(Channel.CONFLATED)
    internal val pointEventChannel = Channel<TrackPointEvent>(Channel.UNLIMITED)
    val fitTrailEvents get() = fitTrailSignal.receiveAsFlow()

    internal var lastStreamTargetsSeed: String? = null
    // TrackersRefreshed carries the *new* roster only; the store's own `trackers` StateFlow has
    // already been overwritten with that same new list by the time the event reaches us (see
    // `TrackerManagementStateStore.publishTrackers`), so a removal diff needs our own prior
    // snapshot rather than reading through the store. Starts empty so the first refresh after
    // (re)start never reports spurious removals.
    internal var lastKnownRosterTrackerIds: Set<String> = emptySet()
    internal var lastBackgroundAtElapsedMs: Long = 0L
    internal var mapReady: Boolean = false
    internal var pendingResumeEvaluation: Boolean = false
    internal var mapSurfaceVisible: Boolean = false
    internal var pendingInitialTrackerForMap: Boolean = true
    internal var runtimeTrailReloadJob: Job? = null
    internal var runtimeTrailReloadPendingReason: TrackerMapTrailReloadReason? = null
    internal val trailReloadMutex = Mutex()
    internal var nextTrailReloadId: Long = 1L
    internal var lastTrailLoadSeed: String? = null
    internal var pendingReopenSingleTrackerLoadId: String? = null
    internal var pendingFitAfterReload: Boolean = false
    internal val filterChangeReactor = TrackerMapFilterChangeReactor()
    internal val historyRepository: TrackerHistoryRepository =
        TrackerAppServices.from(ports.application).trackerHistoryRepository()
    internal val historyIntentDispatcher = TrackerHistoryIntentDispatcher(historyRepository)
    internal val historySessionBoundary = TrackerHistorySessionBoundary()
    internal var lastObservedTrackingRunning: Boolean? = null
    internal var lastObservedLocalRecordingActive: Boolean? = null
    internal var lastRuntimeTrailReloadSignature: String? = null
    internal var lastObservedStreamingSessionActive: Boolean = false
    internal var lastObservedStreamingFailureReason: String? = null
    internal val reconcileTokenMutable = MutableStateFlow(0L)
    internal val runtimeResyncPolicy = TrackerMapRuntimeResyncPolicy()
    internal val reopenOrchestrator = TrackerMapReopenOrchestrator()
    internal val sessionRequestDeduper = TrackerMapSessionRequestDeduper()
    internal val geometryLoadingTracker = TrackerMapGeometryLoadingTracker(
        onLoadingChanged = ::setGeometryLoading,
    )
    internal val geometryReloadCircuitBreaker = MapGeometryReloadCircuitBreaker()
    internal val streamingPlanCache = TrackerMapStreamingPlanCache()

    /**
     * Wall-clock timestamp of when [com.geovault.tracker.services.LiveStreamSubscriptionState]
     * most recently transitioned from healthy to unhealthy while wanted, or `null` if currently
     * healthy/unwanted. Read and written only from the heartbeat collector in
     * [MapStreamingSubsystem], which runs on a single dispatcher, so no additional
     * synchronization is needed. Feeds [com.geovault.tracker.presentation.StreamingBatteryOptimizationHintPolicy].
     */
    internal var streamingUnhealthySinceMs: Long? = null
    internal lateinit var trailLoaderOps: TrackerMapTrailLoaderOps

    internal lateinit var context: MapContextSubsystem
    internal lateinit var display: MapTrailDisplaySubsystem
    internal lateinit var reload: MapTrailReloadSubsystem
    internal lateinit var streaming: MapStreamingSubsystem
    internal lateinit var streamRosterResolver: StreamRosterResolver
    internal lateinit var streamTargetReconciler: StreamTargetReconciler
    internal lateinit var trackPointReducer: TrackPointReducer

    fun start() {
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

    fun trackerRosterForMapChip() = trackerManagementStateStore.trackers.value

    fun onCleared() {
        pointEventChannel.close()
        fitTrailSignal.close()
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
        val visibility = trackerManagementStateStore.mapVisibility.value
        val hiddenGroupIds = visibility?.hidden_group_ids.orEmpty().toSet()
        val hiddenTrackIds = visibility?.hidden_track_ids.orEmpty().toSet()
        val hiddenOwnerTrackerIds = HiddenMapItemsPolicy.hiddenOwnerTrackerIds(trackerManagementStateStore.trackers.value)
        val preferredTrackerId = TrackerMapDisplayIds.effectiveDisplayedTrackerId(state).ifBlank { state.runtime.selectedTrackerId }
        return TrackerMapGroupModePolicy.resolveSelection(
            groups = trackerManagementStateStore.groups.value,
            hiddenGroupIds = hiddenGroupIds,
            hiddenTrackIds = hiddenTrackIds,
            hiddenOwnerTrackerIds = hiddenOwnerTrackerIds,
            rosterTrackerIds = trackerManagementStateStore.trackers.value.mapTo(mutableSetOf()) { it.id.trim() },
            preferredGroupId = state.currentGroupId,
            preferredTrackerId = preferredTrackerId,
        )
    }

    internal fun visibleMapRosterTrackerIds(): Set<String> {
        val trackers = trackerManagementStateStore.trackers.value
        return HiddenMapItemsPolicy.visibleTrackerIdsForMap(
            rosterTrackerIds = trackers.map { it.id },
            mapVisibility = trackerManagementStateStore.mapVisibility.value,
            trackers = trackers,
        )
    }

    internal fun resolveGroupModeOptions(): List<TrackerMapGroupModeOption> {
        val visibility = trackerManagementStateStore.mapVisibility.value
        val hiddenGroupIds = visibility?.hidden_group_ids.orEmpty().toSet()
        val hiddenTrackIds = visibility?.hidden_track_ids.orEmpty().toSet()
        val hiddenOwnerTrackerIds = HiddenMapItemsPolicy.hiddenOwnerTrackerIds(trackerManagementStateStore.trackers.value)
        return TrackerMapGroupModePolicy.resolveEligibleGroups(
            groups = trackerManagementStateStore.groups.value,
            hiddenGroupIds = hiddenGroupIds,
            hiddenTrackIds = hiddenTrackIds,
            hiddenOwnerTrackerIds = hiddenOwnerTrackerIds,
            rosterTrackerIds = trackerManagementStateStore.trackers.value.mapTo(mutableSetOf()) { it.id.trim() },
        )
    }

    internal fun currentActiveSessionStartMs(): Long? {
        return activeSessionStartMsForRuntime(uiStateMutable.value.runtime)
    }

    internal fun activeSessionStartMsForRuntime(runtime: TrackingRuntimeSnapshot): Long? {
        return runtime.sessionStartTimeMs.takeIf { runtime.localRecordingActive && it > 0L }
    }

    internal fun recomposeHistoryForTracker(trackerId: String) {
        val normalized = trackerId.trim()
        if (normalized.isEmpty()) return
        val sessionStart = currentActiveSessionStartMs()
        val keys = historyRepository.snapshots.value.keys.filter { it.normalizedTrackerId == normalized }
        if (keys.isEmpty()) {
            val tracker = trackerManagementStateStore.trackers.value.firstOrNull { it.id.trim() == normalized }
            val window = TrackerHistoryWindowResolver.fromTracker(tracker)
            historyRepository.composeAndPublish(
                key = TrackerHistoryKey(normalized, window),
                activeSessionStartMs = sessionStart,
            )
            return
        }
        for (key in keys) {
            historyRepository.composeAndPublish(
                key = key,
                activeSessionStartMs = sessionStart,
            )
        }
    }

    /**
     * IDLE-ROLLING-WINDOW STALENESS: see [TrackerHistoryRepository.recomputeStaleRollingWindows].
     * Called both periodically (from [MapStreamingSubsystem.startCollectors]'s ticker) and on
     * resume ([MapContextSubsystem.onHostResumed]) so a "last N hours"-style filter re-excludes
     * points that aged out of the window while the tracker was idle, not only while new points
     * are actively arriving.
     */
    internal fun recomputeStaleRollingWindows(): Boolean {
        val locallyRecordedTrackerId = uiStateMutable.value.runtime.locallyRecordedTrackerId.trim()
        val activeSessionStartMs = currentActiveSessionStartMs()
        val changedKeys = historyRepository.recomputeStaleRollingWindows(
            activeSessionStartMsFor = { trackerId ->
                if (trackerId == locallyRecordedTrackerId) activeSessionStartMs else null
            },
        )
        return changedKeys.isNotEmpty()
    }

    internal fun setGeometryLoading(isLoading: Boolean) {
        val current = uiStateMutable.value
        if (current.isGeometryLoading == isLoading) return
        GeoVaultCaptureLog.d(TrackerMapViewModel.TAG, "map_update vm_geometry_loading from=${current.isGeometryLoading} to=$isLoading")
        uiStateMutable.value = current.copy(isGeometryLoading = isLoading)
    }
}
