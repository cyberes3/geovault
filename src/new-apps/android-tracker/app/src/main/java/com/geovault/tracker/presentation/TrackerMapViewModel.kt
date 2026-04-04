package com.geovault.tracker.presentation

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.tracker.MapStreamingServiceHelper
import com.geovault.tracker.TrackingService
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.db.AppDatabase
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.data.TrackerManagementRepository
import com.geovault.tracker.data.TrackerManagementStateStore
import com.geovault.tracker.policy.TrackPointBus
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.location.TrackingLifecycleState
import com.geovault.tracker.services.LiveStreamRuntimeStateStore
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import com.geovault.tracker.services.TrackingRuntimeStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLngBounds

data class TrackerMapUiState(
    val runtime: TrackingRuntimeSnapshot = TrackingRuntimeSnapshot(),
    val trail: List<QueuedLocation> = emptyList(),
    val remoteLastPoints: Map<String, TrackPointEvent> = emptyMap(),
    val activeStreamedTrackerIds: Set<String> = emptySet(),
    val streamTargetIds: Set<String> = emptySet(),
    val displayedTrackerId: String = "",
    val displayedTrackerName: String = "",
    val mode: TrackerMapDisplayMode = TrackerMapDisplayMode.SINGLE_SESSION,
    val followLockEnabled: Boolean = false,
)

class TrackerMapViewModel(application: Application) : AndroidViewModel(application) {
    private companion object {
        const val TAG = "TrackerMapViewModel"
        const val TRAIL_POINT_LIMIT = 4000
    }

    private val appContext = application.applicationContext
    private val dao = AppDatabase.getDatabase(application).locationDao()
    private val trackerManagementRepository: TrackerManagementRepository =
        TrackerAppServices.from(application).trackerManagementRepository()
    private val trackerManagementStateStore: TrackerManagementStateStore =
        TrackerAppServices.from(application).trackerManagementStateStore()

    private val _uiState = MutableStateFlow(TrackerMapUiState())
    val uiState: StateFlow<TrackerMapUiState> = _uiState.asStateFlow()

    private val fitTrailSignal = Channel<Unit>(Channel.CONFLATED)
    val fitTrailEvents = fitTrailSignal.receiveAsFlow()
    private var lastStreamTargetsSeed: String? = null
    private var lastStreamingServiceSeed: String? = null
    private var lastBackgroundAtElapsedMs: Long = 0L
    private var mapReady: Boolean = false
    private var pendingResumeEvaluation: Boolean = false
    private var mapSurfaceVisible: Boolean = false
    private var pendingInitialTrackerForMap: Boolean = false
    private var runtimeTrailReloadJob: Job? = null
    private var runtimeTrailReloadPending: Boolean = false
    private val reopenOrchestrator = TrackerMapReopenOrchestrator()

    init {
        viewModelScope.launch {
            TrackingRuntimeStateStore.state.collect { snap ->
                val startupInProgress = TrackingService.isStartupInProgress
                val effectiveLifecycleState = if (!snap.isRunning && startupInProgress) {
                    TrackingLifecycleState.STARTING
                } else {
                    snap.lifecycleState
                }
                val effectiveRuntime = snap.copy(
                    isRunning = snap.isRunning || startupInProgress,
                    lifecycleState = effectiveLifecycleState
                )
                val current = _uiState.value
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
                _uiState.value = current.copy(
                    runtime = effectiveRuntime,
                    displayedTrackerId = displayedTrackerId,
                    displayedTrackerName = displayedTrackerName
                )
                requestRuntimeTrailReload()
                refreshStreamTargets()
            }
        }
        viewModelScope.launch {
            TrackPointBus.remoteStreamEvents.collectLatest { point ->
                handleTrackPointEvent(point)
            }
        }
        viewModelScope.launch {
            LiveStreamRuntimeStateStore.state.collectLatest { snapshot ->
                _uiState.value = _uiState.value.copy(
                    activeStreamedTrackerIds = snapshot.activeTrackerIds,
                    remoteLastPoints = if (snapshot.isRunning) {
                        _uiState.value.remoteLastPoints.filterKeys { it in snapshot.activeTrackerIds }
                    } else {
                        emptyMap()
                    }
                )
            }
        }
        viewModelScope.launch {
            trackerManagementStateStore.trackers.collectLatest {
                refreshStreamTargets()
            }
        }
        viewModelScope.launch {
            trackerManagementStateStore.events.collectLatest { event ->
                when (event) {
                    is com.geovault.tracker.data.TrackerManagementEvent.HistoryCleared -> {
                        reloadTrailFromDatabase()
                    }
                    else -> Unit
                }
            }
        }
        viewModelScope.launch {
            TrackPointBus.localGpsEvents.collectLatest { point ->
                handleTrackPointEvent(point)
            }
        }
        viewModelScope.launch {
            uiState.collectLatest { state ->
                if (!mapSurfaceVisible) return@collectLatest
                applyStreamingServicePolicy(state)
            }
        }
        refreshStreamTargets()
    }

    fun setMode(mode: TrackerMapDisplayMode) {
        _uiState.value = _uiState.value.copy(mode = mode)
        refreshStreamTargets()
    }

    fun onHostPaused() {
        lastBackgroundAtElapsedMs = SystemClock.elapsedRealtime()
    }

    fun onHostResumed() {
        if (lastBackgroundAtElapsedMs <= 0L || !mapSurfaceVisible) return
        if (!mapReady) {
            pendingResumeEvaluation = true
            return
        }
        evaluateResumeAfterBackground(allowZeroGap = false)
    }

    fun onMapSurfaceVisible() {
        mapSurfaceVisible = true
        if (!mapReady) {
            pendingResumeEvaluation = true
            return
        }
        evaluateResumeAfterBackground(allowZeroGap = true)
        viewModelScope.launch {
            lastStreamingServiceSeed = null
            applyStreamingServicePolicy(_uiState.value)
        }
    }

    fun onMapSurfaceHidden() {
        mapSurfaceVisible = false
        mapReady = false
        lastBackgroundAtElapsedMs = SystemClock.elapsedRealtime()
        lastStreamingServiceSeed = null
        MapStreamingServiceHelper.stopStreaming(appContext)
    }

    fun markPendingInitialTrackerForMap() {
        pendingInitialTrackerForMap = true
    }

    fun setMapReady(isReady: Boolean) {
        mapReady = isReady
        if (!mapReady || !pendingResumeEvaluation) return
        pendingResumeEvaluation = false
        evaluateResumeAfterBackground(allowZeroGap = true)
    }

    private fun evaluateResumeAfterBackground(allowZeroGap: Boolean) {
        val backgroundDurationMs = if (lastBackgroundAtElapsedMs > 0L) {
            SystemClock.elapsedRealtime() - lastBackgroundAtElapsedMs
        } else {
            0L
        }
        if (backgroundDurationMs <= 0L && !allowZeroGap) return
        val state = _uiState.value
        val hasPendingInitialTracker = pendingInitialTrackerForMap
        pendingInitialTrackerForMap = false
        val outcome = reopenOrchestrator.resolve(
            TrackerMapResumeInput(
                trackingRunning = state.runtime.isRunning,
                mapReady = mapReady,
                showAllTrackers = state.mode == TrackerMapDisplayMode.ALL_QUEUE,
                mapViewContext = if (state.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
                    TrackerMapViewContext.GROUP
                } else {
                    TrackerMapViewContext.SINGLE_TRACKER
                },
                activeStreamedTrackerIds = state.activeStreamedTrackerIds,
                currentGroupTrackIds = if (state.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
                    state.streamTargetIds
                } else {
                    emptySet()
                },
                selectedTrackerId = state.runtime.selectedTrackerId,
                displayedTrackerId = effectiveDisplayedTrackerId(state),
                hasTrailPoints = state.trail.isNotEmpty(),
                hasPendingInitialTracker = hasPendingInitialTracker,
                backgroundedDurationMs = backgroundDurationMs
            )
        )
        outcome.invariants
            .filter { !it.satisfied }
            .forEach { invariant ->
                Log.w(TAG, "Reopen invariant violation ${invariant.invariant}: ${invariant.details}")
            }
        viewModelScope.launch {
            applyReopenDecision(outcome.decision)
            refreshStreamTargets()
            if (mapSurfaceVisible) {
                lastStreamingServiceSeed = null
                applyStreamingServicePolicy(_uiState.value)
            }
        }
        lastBackgroundAtElapsedMs = 0L
        pendingResumeEvaluation = false
    }

    private suspend fun applyReopenDecision(decision: TrackerMapResumeDecision) {
        when (decision) {
            TrackerMapResumeDecision.NoOp -> Unit
            TrackerMapResumeDecision.MultiContextNoStreaming -> Unit
            is TrackerMapResumeDecision.StartMultiContextStreaming -> {
                val ids = decision.trackerIds.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()
                _uiState.value = _uiState.value.copy(streamTargetIds = ids)
                if (ids.isNotEmpty()) {
                    MapStreamingServiceHelper.startStreaming(
                        context = appContext,
                        trackerIds = ids
                    )
                } else {
                    MapStreamingServiceHelper.stopStreaming(appContext)
                }
            }
            TrackerMapResumeDecision.ClearSingleTrackerState -> {
                _uiState.value = _uiState.value.copy(
                    displayedTrackerId = "",
                    displayedTrackerName = "",
                    remoteLastPoints = emptyMap(),
                    streamTargetIds = emptySet()
                )
                MapStreamingServiceHelper.stopStreaming(appContext)
            }
            is TrackerMapResumeDecision.LoadSingleTrackerRuntime,
            is TrackerMapResumeDecision.LoadSingleTrackerBootstrap -> {
                val trackerId = when (decision) {
                    is TrackerMapResumeDecision.LoadSingleTrackerRuntime -> decision.trackerId
                    is TrackerMapResumeDecision.LoadSingleTrackerBootstrap -> decision.trackerId
                }
                if (trackerId.isNotBlank()) {
                    val runtime = _uiState.value.runtime
                    val trackerName = if (trackerId == runtime.selectedTrackerId) {
                        runtime.selectedTrackerName
                    } else {
                        _uiState.value.displayedTrackerName
                    }
                    _uiState.value = _uiState.value.copy(
                        displayedTrackerId = trackerId,
                        displayedTrackerName = trackerName
                    )
                }
                reloadTrailFromDatabase()
                lastStreamingServiceSeed = null
                applyStreamingServicePolicy(_uiState.value)
            }
            TrackerMapResumeDecision.RestartDisplayedTrackerStreaming -> {
                lastStreamingServiceSeed = null
                applyStreamingServicePolicy(_uiState.value)
            }
        }
    }

    fun setFollowLock(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(followLockEnabled = enabled)
    }

    fun clearFollowLockAfterUserGesture() {
        if (_uiState.value.followLockEnabled) {
            _uiState.value = _uiState.value.copy(followLockEnabled = false)
        }
    }

    fun requestFitTrail() {
        fitTrailSignal.trySend(Unit)
    }

    fun buildMapRenderState(): com.geovault.common.maps.render.MapRenderState {
        val s = _uiState.value
        return TrackerMapStateTransforms.buildRenderState(
            mode = s.mode,
            trail = s.trail,
            runtime = s.runtime,
            remoteLastPoints = s.remoteLastPoints,
            activeStreamedTrackerIds = s.activeStreamedTrackerIds,
        )
    }

    fun trailBoundsOrNull(): LatLngBounds? {
        val s = _uiState.value
        val effectiveTrail = TrackerMapStateTransforms.effectiveTrail(
            mode = s.mode,
            trail = s.trail,
            runtime = s.runtime
        )
        return TrackerMapStateTransforms.trailBounds(effectiveTrail)
            ?: singlePointBoundsFromRuntime(s.runtime)
    }

    private fun singlePointBoundsFromRuntime(runtime: TrackingRuntimeSnapshot): LatLngBounds? {
        val lat = runtime.lastTrackedLatitude ?: return null
        val lon = runtime.lastTrackedLongitude ?: return null
        return LatLngBounds.from(lat, lon, lat, lon)
    }

    private fun refreshStreamTargets() {
        viewModelScope.launch {
            val state = _uiState.value
            val trackerRosterSignature = trackerManagementStateStore.trackers.value
                .map { it.id.trim() }
                .filter { it.isNotEmpty() }
                .sorted()
                .joinToString(separator = ",")
            val seed =
                "${state.mode}|${state.runtime.isRunning}|${state.runtime.selectedTrackerId}|${effectiveDisplayedTrackerId(state)}|$trackerRosterSignature"
            if (seed == lastStreamTargetsSeed) return@launch
            lastStreamTargetsSeed = seed
            val streamIds = when (state.mode) {
                TrackerMapDisplayMode.SINGLE_SESSION -> {
                    val displayedId = effectiveDisplayedTrackerId(state)
                    if (displayedId.isBlank()) {
                        emptySet()
                    } else if (
                        state.runtime.selectedTrackerId.isNotBlank() &&
                        displayedId == state.runtime.selectedTrackerId
                    ) {
                        emptySet()
                    } else {
                        setOf(displayedId)
                    }
                }
                TrackerMapDisplayMode.GROUP_PLACEHOLDER -> emptySet()
                TrackerMapDisplayMode.ALL_QUEUE -> {
                    when (val loaded = trackerManagementRepository.loadTrackers(forceRefresh = false)) {
                        is RepositoryResult.Success -> {
                            val baseIds = loaded.data.map { it.id }.filter { it.isNotBlank() }.toSet()
                            if (state.runtime.isRunning && state.runtime.selectedTrackerId.isNotBlank()) {
                                baseIds - state.runtime.selectedTrackerId
                            } else {
                                baseIds
                            }
                        }
                        is RepositoryResult.Failure -> {
                            val fallbackIds = trackerManagementStateStore.trackers.value
                                .map { it.id.trim() }
                                .filter { it.isNotEmpty() }
                                .toSet()
                            if (state.runtime.isRunning && state.runtime.selectedTrackerId.isNotBlank()) {
                                fallbackIds - state.runtime.selectedTrackerId
                            } else {
                                fallbackIds
                            }
                        }
                    }
                }
            }
            _uiState.value = _uiState.value.copy(streamTargetIds = streamIds)
        }
    }

    private suspend fun reloadTrailFromDatabase() {
        val databaseTrail = withContext(Dispatchers.IO) {
            dao.getRecentChronological(TRAIL_POINT_LIMIT)
        }
        val currentOverlay = _uiState.value.trail.filter { it.id <= 0L }
        val mergedTrail = mergeOverlayPoints(databaseTrail, currentOverlay).takeLast(TRAIL_POINT_LIMIT)
        _uiState.value = _uiState.value.copy(trail = mergedTrail)
    }

    private fun requestRuntimeTrailReload() {
        if (runtimeTrailReloadJob?.isActive == true) {
            runtimeTrailReloadPending = true
            return
        }
        runtimeTrailReloadJob = viewModelScope.launch {
            do {
                runtimeTrailReloadPending = false
                reloadTrailFromDatabase()
            } while (runtimeTrailReloadPending)
        }
    }

    private fun mergeOverlayPoints(
        databaseTrail: List<QueuedLocation>,
        overlayPoints: List<QueuedLocation>
    ): List<QueuedLocation> {
        if (overlayPoints.isEmpty()) return databaseTrail
        val merged = databaseTrail.toMutableList()
        overlayPoints.forEach { overlay ->
            val duplicate = merged.any { existing ->
                existing.time == overlay.time &&
                    existing.latitude == overlay.latitude &&
                    existing.longitude == overlay.longitude
            }
            if (!duplicate) merged.add(overlay)
        }
        return merged.sortedBy { it.time }
    }

    private fun handleTrackPointEvent(point: TrackPointEvent) {
        val state = _uiState.value
        val shouldAccept = TrackerMapPointAcceptancePolicy.shouldAccept(
            event = point,
            input = TrackerMapPointAcceptanceInput(
                trackingRunning = state.runtime.isRunning,
                mode = state.mode,
                displayedTrackerId = effectiveDisplayedTrackerId(state),
                selectedTrackerId = state.runtime.selectedTrackerId,
                activeStreamedTrackerIds = state.activeStreamedTrackerIds
            )
        )
        if (!shouldAccept) return

        when (point.source) {
            TrackPointSource.REMOTE_STREAM -> {
                val current = state.remoteLastPoints.toMutableMap()
                current[point.trackId] = point
                _uiState.value = state.copy(remoteLastPoints = current)
            }
            TrackPointSource.LOCAL_GPS -> {
                val localOverlayPoint = QueuedLocation(
                    id = 0L,
                    time = point.timestampMs,
                    latitude = point.lat,
                    longitude = point.lon,
                    altitude = null,
                    speed = null,
                    bearing = null,
                    accuracy = point.accuracyMeters,
                    sat = null,
                    prov = "local_gps",
                    dist = null
                )
                val trail = state.trail
                val last = trail.lastOrNull()
                val isDuplicateTail = last != null &&
                    last.time == localOverlayPoint.time &&
                    last.latitude == localOverlayPoint.latitude &&
                    last.longitude == localOverlayPoint.longitude
                if (!isDuplicateTail) {
                    val nextTrail = (trail + localOverlayPoint).takeLast(TRAIL_POINT_LIMIT)
                    _uiState.value = state.copy(trail = nextTrail)
                }
            }
        }
    }

    private fun applyStreamingServicePolicy(state: TrackerMapUiState) {
        val streamIdsSignature = state.streamTargetIds.toList().sorted().joinToString(separator = ",")
        val effectiveDisplayedId = effectiveDisplayedTrackerId(state)
        val effectiveDisplayedName = effectiveDisplayedTrackerName(state)
        val seed =
            "${state.mode}|${state.runtime.isRunning}|$streamIdsSignature|$effectiveDisplayedId|${state.runtime.selectedTrackerId}|$effectiveDisplayedName"
        if (seed == lastStreamingServiceSeed) return
        lastStreamingServiceSeed = seed

        if (state.mode == TrackerMapDisplayMode.SINGLE_SESSION) {
            MapStreamingServiceHelper.updateStreamingForDisplayedTracker(
                displayedTrackerId = effectiveDisplayedId,
                displayedTrackerName = effectiveDisplayedName.takeIf { it.isNotBlank() },
                selectedTrackerId = state.runtime.selectedTrackerId,
                mapMode = state.mode,
                startStreaming = { trackerIds, trackerName ->
                    MapStreamingServiceHelper.startStreaming(
                        context = appContext,
                        trackerIds = trackerIds,
                        trackerName = trackerName
                    )
                },
                stopStreaming = {
                    MapStreamingServiceHelper.stopStreaming(appContext)
                }
            )
            return
        }

        if (state.streamTargetIds.isNotEmpty()) {
            val streamDisplayName = if (state.streamTargetIds.size == 1) {
                effectiveDisplayedName.takeIf { it.isNotBlank() }
            } else {
                null
            }
            MapStreamingServiceHelper.startStreaming(
                context = appContext,
                trackerIds = state.streamTargetIds,
                trackerName = streamDisplayName
            )
        } else {
            MapStreamingServiceHelper.stopStreaming(appContext)
        }
    }

    private fun effectiveDisplayedTrackerId(state: TrackerMapUiState): String {
        return state.displayedTrackerId.trim().ifBlank { state.runtime.selectedTrackerId.trim() }
    }

    private fun effectiveDisplayedTrackerName(state: TrackerMapUiState): String {
        return state.displayedTrackerName.trim().ifBlank { state.runtime.selectedTrackerName.trim() }
    }
}
