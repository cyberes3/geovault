package com.geovault.tracker.presentation

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.tracker.MapStreamingServiceHelper
import com.geovault.tracker.TrackingService
import com.geovault.tracker.Tracker
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
    val allQueueTrailsByTracker: Map<String, List<QueuedLocation>> = emptyMap(),
    val remoteLastPoints: Map<String, TrackPointEvent> = emptyMap(),
    val activeStreamedTrackerIds: Set<String> = emptySet(),
    val streamTargetIds: Set<String> = emptySet(),
    val currentGroupId: String = "",
    val groupModeOptions: List<TrackerMapGroupModeOption> = emptyList(),
    val displayedTrackerId: String = "",
    val displayedTrackerName: String = "",
    val selectedMapTracker: TrackerMapSelectionCard? = null,
    val selectionLockTrackerId: String = "",
    val mode: TrackerMapDisplayMode = TrackerMapDisplayMode.SINGLE_SESSION,
    val followLockEnabled: Boolean = false,
    val isHistoryLoading: Boolean = false,
)

data class TrackerMapSelectionCard(
    val trackerId: String,
    val trackerName: String,
    val latitude: Double,
    val longitude: Double,
    val lastUpdatedMs: Long?,
    val accuracyMeters: Float?,
    val isOwned: Boolean,
)

class TrackerMapViewModel(application: Application) : AndroidViewModel(application) {
    internal enum class HistoryClearRefreshAction {
        REFRESH_GROUP_OR_ALL,
        REFRESH_DISPLAYED_SINGLE,
        REFRESH_SELECTED_SINGLE,
        NO_OP
    }

    companion object {
        const val TAG = "TrackerMapViewModel"
        const val TRAIL_POINT_LIMIT = 4000
        private const val SESSION_ANCHOR_RESYNC_MS = 15_000L

        @JvmStatic
        internal fun resolveStreamTargetIds(
            mode: TrackerMapDisplayMode,
            runtimeRunning: Boolean,
            selectedTrackerId: String,
            displayedTrackerId: String,
            rosterTrackerIds: Set<String>
        ): Set<String> {
            val normalizedSelected = selectedTrackerId.trim()
            val normalizedDisplayed = displayedTrackerId.trim()
            val normalizedRoster = rosterTrackerIds.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()
            return when (mode) {
                TrackerMapDisplayMode.SINGLE_SESSION -> {
                    if (normalizedDisplayed.isBlank()) {
                        emptySet()
                    } else if (normalizedSelected.isNotBlank() && normalizedDisplayed == normalizedSelected) {
                        emptySet()
                    } else {
                        setOf(normalizedDisplayed)
                    }
                }
                TrackerMapDisplayMode.GROUP_PLACEHOLDER -> emptySet()
                TrackerMapDisplayMode.ALL_QUEUE -> {
                    if (runtimeRunning && normalizedSelected.isNotBlank()) {
                        normalizedRoster - normalizedSelected
                    } else {
                        normalizedRoster
                    }
                }
            }
        }

        @JvmStatic
        internal fun resolveAllowSessionReset(
            pendingReopenTrackerId: String?,
            eventTrackId: String
        ): Boolean {
            val pending = pendingReopenTrackerId?.trim().orEmpty()
            if (pending.isEmpty()) return true
            return pending != eventTrackId.trim()
        }

        @JvmStatic
        internal fun resolveHistoryClearRefreshAction(
            mode: TrackerMapDisplayMode,
            displayedTrackerId: String,
            selectedTrackerId: String,
            clearedTrackerId: String
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
    private val pointEventChannel = Channel<TrackPointEvent>(Channel.UNLIMITED)
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
    private var lastTrailLoadSeed: String? = null
    private val remoteSessionStartByTrack = mutableMapOf<String, Long>()
    private var pendingReopenSingleTrackerLoadId: String? = null
    private var sessionAnchorResyncTrackerId: String? = null
    private var sessionAnchorResyncUntilElapsedMs: Long = 0L
    private val recentDataWindowByTracker = mutableMapOf<String, String?>()
    private var lastObservedTrackingRunning: Boolean? = null
    private val runtimeResyncPolicy = TrackerMapRuntimeResyncPolicy()
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
                    isRunning = snap.isRunning,
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
                val runtimeResyncDecision = runtimeResyncPolicy.decide(
                    previousIsRunning = lastObservedTrackingRunning,
                    currentIsRunning = snap.isRunning,
                    mapReady = mapReady,
                    mapViewContext = if (current.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
                        TrackerMapViewContext.GROUP
                    } else {
                        TrackerMapViewContext.SINGLE_TRACKER
                    }
                )
                lastObservedTrackingRunning = snap.isRunning
                requestRuntimeTrailReload()
                refreshStreamTargets()
                if (runtimeResyncDecision.restartDisplayedStreaming && mapSurfaceVisible) {
                    lastStreamingServiceSeed = null
                    applyStreamingServicePolicy(_uiState.value)
                }
            }
        }
        viewModelScope.launch {
            TrackPointBus.remoteStreamEvents.collect { point ->
                pointEventChannel.send(point)
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
                requestRuntimeTrailReload()
                refreshStreamTargets()
            }
        }
        viewModelScope.launch {
            trackerManagementStateStore.groups.collectLatest {
                requestRuntimeTrailReload()
                refreshStreamTargets()
            }
        }
        viewModelScope.launch {
            trackerManagementStateStore.mapVisibility.collectLatest {
                requestRuntimeTrailReload()
                refreshStreamTargets()
            }
        }
        viewModelScope.launch {
            trackerManagementStateStore.events.collectLatest { event ->
                when (event) {
                    is com.geovault.tracker.data.TrackerManagementEvent.HistoryCleared -> {
                        val state = _uiState.value
                        when (
                            resolveHistoryClearRefreshAction(
                                mode = state.mode,
                                displayedTrackerId = state.displayedTrackerId,
                                selectedTrackerId = state.runtime.selectedTrackerId,
                                clearedTrackerId = event.trackerId
                            )
                        ) {
                            HistoryClearRefreshAction.REFRESH_GROUP_OR_ALL -> {
                                reloadTrailFromDatabase(force = true)
                            }
                            HistoryClearRefreshAction.REFRESH_DISPLAYED_SINGLE,
                            HistoryClearRefreshAction.REFRESH_SELECTED_SINGLE -> {
                                primeSessionAnchorResync(event.trackerId)
                                reloadTrailFromDatabase(force = true)
                            }
                            HistoryClearRefreshAction.NO_OP -> Unit
                        }
                    }
                    is com.geovault.tracker.data.TrackerManagementEvent.TrackerUpserted -> {
                        val trackerId = event.tracker.id
                        val newWindow = event.tracker.settingString("recent_data_window")
                        val oldWindow = recentDataWindowByTracker.put(trackerId, newWindow)
                        val state = _uiState.value
                        val displayedId = effectiveDisplayedTrackerId(state)
                        if (oldWindow != null &&
                            oldWindow != newWindow &&
                            state.mode == TrackerMapDisplayMode.SINGLE_SESSION &&
                            !state.runtime.isRunning &&
                            displayedId == trackerId
                        ) {
                            primeSessionAnchorResync(trackerId)
                            reloadTrailFromDatabase(force = true)
                        }
                    }
                    else -> Unit
                }
            }
        }
        viewModelScope.launch {
            TrackPointBus.localGpsEvents.collect { point ->
                pointEventChannel.send(point)
            }
        }
        viewModelScope.launch {
            for (point in pointEventChannel) {
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
        val groupOptions = if (mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
            resolveGroupModeOptions()
        } else {
            emptyList()
        }
        val preferredGroupId = if (mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
            val currentGroup = _uiState.value.currentGroupId.trim()
            currentGroup.takeIf { candidate -> groupOptions.any { it.groupId == candidate } }
                ?: groupOptions.firstOrNull()?.groupId.orEmpty()
        } else {
            ""
        }
        _uiState.value = _uiState.value.copy(
            mode = mode,
            currentGroupId = preferredGroupId,
            groupModeOptions = groupOptions,
            selectedMapTracker = null,
            selectionLockTrackerId = "",
        )
        if (mode != TrackerMapDisplayMode.SINGLE_SESSION) {
            pendingReopenSingleTrackerLoadId = null
        }
        requestRuntimeTrailReload()
        refreshStreamTargets()
    }

    fun setGroupModeGroup(groupId: String) {
        val normalized = groupId.trim()
        if (normalized.isEmpty()) return
        val state = _uiState.value
        if (state.currentGroupId == normalized && state.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
            return
        }
        _uiState.value = state.copy(
            currentGroupId = normalized,
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            selectedMapTracker = null,
            selectionLockTrackerId = "",
        )
        pendingReopenSingleTrackerLoadId = null
        requestRuntimeTrailReload()
        refreshStreamTargets()
    }

    fun openTrackerOnMap(trackerId: String, trackerName: String?) {
        val normalizedId = trackerId.trim()
        if (normalizedId.isEmpty()) return
        val state = _uiState.value
        val resolvedName = trackerName?.trim().orEmpty().ifBlank {
            if (normalizedId == state.runtime.selectedTrackerId) {
                state.runtime.selectedTrackerName
            } else {
                state.displayedTrackerName
            }
        }
        _uiState.value = state.copy(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = normalizedId,
            displayedTrackerName = resolvedName,
            currentGroupId = "",
            groupModeOptions = emptyList(),
            selectedMapTracker = null,
            selectionLockTrackerId = "",
        )
        pendingReopenSingleTrackerLoadId = normalizedId
        requestRuntimeTrailReload()
        refreshStreamTargets()
    }

    fun openGroupOnMap(groupId: String) {
        val normalizedId = groupId.trim()
        if (normalizedId.isEmpty()) return
        val groupOptions = resolveGroupModeOptions()
        val resolvedGroupId = normalizedId.takeIf { candidate ->
            groupOptions.any { it.groupId == candidate }
        } ?: groupOptions.firstOrNull()?.groupId.orEmpty()
        _uiState.value = _uiState.value.copy(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            currentGroupId = resolvedGroupId,
            groupModeOptions = groupOptions,
            selectedMapTracker = null,
            selectionLockTrackerId = "",
        )
        pendingReopenSingleTrackerLoadId = null
        requestRuntimeTrailReload()
        refreshStreamTargets()
    }

    fun restoreSelectedTrackerAfterStreamingStop() {
        val state = _uiState.value
        if (state.runtime.isRunning) return
        val selectedId = state.runtime.selectedTrackerId.trim()
        if (selectedId.isEmpty()) return
        _uiState.value = state.copy(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = selectedId,
            displayedTrackerName = state.runtime.selectedTrackerName,
            currentGroupId = "",
            groupModeOptions = emptyList(),
            selectedMapTracker = null,
            selectionLockTrackerId = "",
        )
        pendingReopenSingleTrackerLoadId = selectedId
        requestRuntimeTrailReload()
        refreshStreamTargets()
    }

    fun resolveListNavigationTarget(preferredTrackerIdOverride: String? = null): MapListNavigationTarget {
        val state = _uiState.value
        val preferredTrackerId = preferredTrackerIdOverride?.trim().orEmpty().ifBlank {
            effectiveDisplayedTrackerId(state)
        }.ifBlank {
            state.runtime.selectedTrackerId.trim()
        }.ifBlank { "" }
        val preferredTrackerOwned = trackerManagementStateStore.trackers.value
            .firstOrNull { it.id == preferredTrackerId }
            ?.isOwner()
        val currentGroupOwned = trackerManagementStateStore.groups.value
            .firstOrNull { it.id == state.currentGroupId.trim() }
            ?.isOwner()
        return MapListNavigationPolicy.resolve(
            mode = state.mode,
            currentGroupId = state.currentGroupId,
            preferredTrackerId = preferredTrackerId,
            isCurrentGroupOwned = currentGroupOwned,
            isPreferredTrackerOwned = preferredTrackerOwned,
        )
    }

    fun selectMapTrackerFromTap(trackerId: String) {
        val normalizedTrackerId = trackerId.trim()
        if (normalizedTrackerId.isEmpty()) return
        val state = _uiState.value
        _uiState.value = state.copy(
            selectedMapTracker = buildSelectionCard(state, normalizedTrackerId)
        )
    }

    fun clearMapTrackerSelection() {
        val state = _uiState.value
        if (state.selectedMapTracker == null) return
        val clearLock = state.selectionLockTrackerId == state.selectedMapTracker.trackerId
        _uiState.value = state.copy(
            selectedMapTracker = null,
            selectionLockTrackerId = if (clearLock) "" else state.selectionLockTrackerId,
        )
    }

    fun focusSelectedTrackerOnMap() {
        val state = _uiState.value
        val selection = state.selectedMapTracker ?: return
        openTrackerOnMap(selection.trackerId, selection.trackerName)
    }

    fun toggleSelectedTrackerLock() {
        val state = _uiState.value
        val selection = state.selectedMapTracker ?: return
        val selectedId = selection.trackerId.trim()
        if (selectedId.isEmpty()) return
        _uiState.value = state.copy(
            selectionLockTrackerId = if (state.selectionLockTrackerId == selectedId) "" else selectedId,
            // Local follow-lock is for device tracking; keep selection lock mutually exclusive.
            followLockEnabled = false,
        )
    }

    fun selectionLockPointOrNull(): Pair<Double, Double>? {
        val state = _uiState.value
        val trackerId = state.selectionLockTrackerId.trim()
        if (trackerId.isEmpty()) return null
        val point = resolveTrackerPointData(state, trackerId) ?: return null
        return point.latitude to point.longitude
    }

    private fun buildSelectionCard(
        state: TrackerMapUiState,
        trackerId: String
    ): TrackerMapSelectionCard? {
        val tracker = trackerManagementStateStore.trackers.value.firstOrNull { it.id == trackerId }
        val point = resolveTrackerPointData(state, trackerId) ?: return null
        val trackerName = tracker?.name
            ?.takeIf { it.isNotBlank() }
            ?: state.displayedTrackerName.takeIf { trackerId == state.displayedTrackerId && it.isNotBlank() }
            ?: state.runtime.selectedTrackerName.takeIf { trackerId == state.runtime.selectedTrackerId && it.isNotBlank() }
            ?: trackerId
        return TrackerMapSelectionCard(
            trackerId = trackerId,
            trackerName = trackerName,
            latitude = point.latitude,
            longitude = point.longitude,
            lastUpdatedMs = point.lastUpdatedMs,
            accuracyMeters = point.accuracyMeters,
            isOwned = tracker?.isOwner() == true,
        )
    }

    private data class ResolvedTrackerPoint(
        val latitude: Double,
        val longitude: Double,
        val lastUpdatedMs: Long?,
        val accuracyMeters: Float?,
    )

    private fun resolveTrackerPointData(
        state: TrackerMapUiState,
        trackerId: String
    ): ResolvedTrackerPoint? {
        val normalizedId = trackerId.trim()
        if (normalizedId.isEmpty()) return null
        val tracker = trackerManagementStateStore.trackers.value.firstOrNull { it.id == normalizedId }
        val remotePoint = state.remoteLastPoints[normalizedId]
        val singleTrailPoint = state.trail.lastOrNull()
            ?.takeIf {
                normalizedId == effectiveDisplayedTrackerId(state) || normalizedId == state.runtime.selectedTrackerId
            }
        val trackerLastPoint = tracker?.last_point
        val latitude = when {
            remotePoint != null -> remotePoint.lat
            singleTrailPoint != null -> singleTrailPoint.latitude
            trackerLastPoint != null && trackerLastPoint.size >= 2 -> trackerLastPoint[1]
            else -> return null
        }
        val longitude = when {
            remotePoint != null -> remotePoint.lon
            singleTrailPoint != null -> singleTrailPoint.longitude
            trackerLastPoint != null && trackerLastPoint.size >= 2 -> trackerLastPoint[0]
            else -> return null
        }
        return ResolvedTrackerPoint(
            latitude = latitude,
            longitude = longitude,
            lastUpdatedMs = remotePoint?.timestampMs ?: singleTrailPoint?.time ?: tracker?.updated_at,
            accuracyMeters = remotePoint?.accuracyMeters ?: singleTrailPoint?.accuracy,
        )
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
        val groupSelection = resolveGroupModeSelection(state)
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
                    groupSelection.trackerIds
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
            lastBackgroundAtElapsedMs = 0L
            pendingResumeEvaluation = false
        }
    }

    private suspend fun applyReopenDecision(decision: TrackerMapResumeDecision) {
        when (decision) {
            TrackerMapResumeDecision.NoOp -> Unit
            TrackerMapResumeDecision.MultiContextNoStreaming -> Unit
            is TrackerMapResumeDecision.StartMultiContextStreaming -> {
                pendingReopenSingleTrackerLoadId = null
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
                pendingReopenSingleTrackerLoadId = null
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
                pendingReopenSingleTrackerLoadId = trackerId.takeIf { it.isNotBlank() }
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
                reloadTrailFromDatabase(force = true)
                if (pendingReopenSingleTrackerLoadId == trackerId) {
                    pendingReopenSingleTrackerLoadId = null
                }
                lastStreamingServiceSeed = null
                applyStreamingServicePolicy(_uiState.value)
            }
            TrackerMapResumeDecision.RestartDisplayedTrackerStreaming -> {
                pendingReopenSingleTrackerLoadId = null
                lastStreamingServiceSeed = null
                applyStreamingServicePolicy(_uiState.value)
            }
        }
    }

    fun setFollowLock(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(
            followLockEnabled = enabled,
            selectionLockTrackerId = if (enabled) "" else _uiState.value.selectionLockTrackerId,
        )
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
        val trackerColors = trackerManagementStateStore.trackers.value.associate { it.id to (it.color ?: "") }
        return TrackerMapStateTransforms.buildRenderState(
            mode = s.mode,
            trail = s.trail,
            runtime = s.runtime,
            remoteLastPoints = s.remoteLastPoints,
            activeStreamedTrackerIds = s.activeStreamedTrackerIds,
            allQueueTrailsByTracker = s.allQueueTrailsByTracker,
            trackerColorById = trackerColors,
        )
    }

    fun trailBoundsOrNull(): LatLngBounds? {
        val s = _uiState.value
        if (s.mode == TrackerMapDisplayMode.ALL_QUEUE || s.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
            return TrackerMapStateTransforms.multiTrailBounds(s.allQueueTrailsByTracker)
                ?: TrackerMapStateTransforms.trailBounds(s.trail)
                ?: singlePointBoundsFromRuntime(s.runtime)
        }
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
            val groupSelection = resolveGroupModeSelection(state)
            val seed = TrackerMapReloadSeedPolicy.streamSeed(
                TrackerMapStreamSeedInput(
                    mode = state.mode,
                    runtimeRunning = state.runtime.isRunning,
                    selectedTrackerId = state.runtime.selectedTrackerId,
                    displayedTrackerId = effectiveDisplayedTrackerId(state),
                    rosterTrackerIds = trackerManagementStateStore.trackers.value.map { it.id },
                    groupSelection = groupSelection
                )
            )
            if (seed == lastStreamTargetsSeed) return@launch
            lastStreamTargetsSeed = seed
            val streamTargetResult = TrackerMapStreamTargetCoordinator.resolve(
                TrackerMapStreamTargetInput(
                    mode = state.mode,
                    runtimeRunning = state.runtime.isRunning,
                    selectedTrackerId = state.runtime.selectedTrackerId,
                    displayedTrackerId = effectiveDisplayedTrackerId(state),
                    rosterTrackerIds = trackerManagementStateStore.trackers.value
                        .map { it.id.trim() }
                        .filter { it.isNotEmpty() }
                        .toSet(),
                    groupSelection = groupSelection
                )
            )
            _uiState.value = _uiState.value.copy(
                streamTargetIds = streamTargetResult.streamTargetIds,
                currentGroupId = if (state.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
                    streamTargetResult.resolvedGroupId
                } else {
                    state.currentGroupId
                },
                groupModeOptions = if (state.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
                    resolveGroupModeOptions()
                } else {
                    emptyList()
                },
            )
        }
    }

    private suspend fun reloadTrailFromDatabase(force: Boolean = false) {
        val state = _uiState.value
        val activeTrackerId = effectiveDisplayedTrackerId(state)
        val groupSelection = resolveGroupModeSelection(state)
        val rosterTrackerIds = trackerManagementStateStore.trackers.value.map { it.id }.toSet()
        val seed = TrackerMapReloadSeedPolicy.trailSeed(
            TrackerMapTrailSeedInput(
                mode = state.mode,
                runtimeRunning = state.runtime.isRunning,
                activeTrackerId = activeTrackerId,
                sessionVisibleBoundaryId = state.runtime.sessionVisibleBoundaryId,
                rosterTrackerIds = rosterTrackerIds,
                groupSelection = groupSelection
            )
        )
        if (!force && lastTrailLoadSeed == seed) return
        lastTrailLoadSeed = seed
        _uiState.value = _uiState.value.copy(isHistoryLoading = true)

        try {
            val plan = TrackerMapTrailReloadCoordinator.resolvePlan(
                TrackerMapTrailReloadInput(
                    mode = state.mode,
                    runtimeRunning = state.runtime.isRunning,
                    activeTrackerId = activeTrackerId,
                    rosterTrackerIds = rosterTrackerIds,
                    groupSelection = groupSelection
                )
            )
            val (trail, allQueueTrailsByTracker) = when (plan.source) {
                TrackerMapTrailSource.SINGLE_SERVER -> {
                    loadSingleTrackerTrailFromServer(plan.singleTrackerId) to emptyMap()
                }
                TrackerMapTrailSource.MULTI_SERVER -> {
                    val multiTrails = loadTrailsForTrackerIds(plan.trackerIds).toMutableMap()
                    plan.overlayTrackerId?.let { overlayTrackerId ->
                        multiTrails[overlayTrackerId] = loadQueueTrailWithOverlay()
                    }
                    val fallbackTrail = multiTrails[plan.fallbackTrackerId].orEmpty()
                    fallbackTrail to multiTrails.toMap()
                }
                TrackerMapTrailSource.SINGLE_QUEUE -> {
                    loadQueueTrailWithOverlay() to emptyMap()
                }
            }
            _uiState.value = state.copy(
                trail = trail,
                allQueueTrailsByTracker = allQueueTrailsByTracker,
                currentGroupId = if (state.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
                    plan.resolvedGroupId
                } else {
                    state.currentGroupId
                },
                groupModeOptions = if (state.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
                    resolveGroupModeOptions()
                } else {
                    emptyList()
                },
                isHistoryLoading = false,
            )
        } finally {
            _uiState.value = _uiState.value.copy(isHistoryLoading = false)
        }
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

    private suspend fun loadQueueTrailWithOverlay(): List<QueuedLocation> {
        val databaseTrail = withContext(Dispatchers.IO) {
            dao.getRecentChronological(TRAIL_POINT_LIMIT)
        }
        val currentOverlay = _uiState.value.trail.filter { it.id <= 0L }
        return mergeOverlayPoints(databaseTrail, currentOverlay).takeLast(TRAIL_POINT_LIMIT)
    }

    private suspend fun loadSingleTrackerTrailFromServer(trackerId: String): List<QueuedLocation> {
        return TrackerMapTrailDataCoordinator.loadSingleTrackerTrail(
            trackerId = trackerId,
            loadTrackerCoordinates = { id -> trackerManagementRepository.loadTrackerCoordinates(id) },
            loadTrackerGeometry = { id -> trackerManagementRepository.loadTrackerGeometry(id) },
            loadQueueTrailWithOverlay = { loadQueueTrailWithOverlay() },
            resolveSessionStartMs = { pointParams ->
                TrackerMapSessionWindowPolicy.resolveLatestSessionStartMs(pointParams)
            },
            onSessionStartResolved = { id, latestSessionStart ->
                if (latestSessionStart != null) {
                    remoteSessionStartByTrack[id] = latestSessionStart
                }
            },
            onSessionAnchorResolved = { id -> clearSessionAnchorResync(id) },
            mergeCoordinates = { geometryCoords, responseCoords ->
                TrackerMapCoordinateMergePolicy.mergedCoordinates(
                    geometryCoords = geometryCoords,
                    responseCoords = responseCoords
                )
            },
            mapCoordinatesToTrail = { merged -> mapCoordinatesToTrail(merged) }
        )
    }

    private suspend fun loadTrailsForTrackerIds(trackerIds: Collection<String>): Map<String, List<QueuedLocation>> {
        return TrackerMapTrailDataCoordinator.loadTrailsForTrackerIds(
            trackerIds = trackerIds,
            loadTrackersGeometry = { ids -> trackerManagementRepository.loadTrackersGeometry(ids) },
            loadTrackerCoordinates = { id -> trackerManagementRepository.loadTrackerCoordinates(id) },
            resolveSessionStartMs = { pointParams ->
                TrackerMapSessionWindowPolicy.resolveLatestSessionStartMs(pointParams)
            },
            onSessionStartResolved = { id, latestSessionStart ->
                if (latestSessionStart != null) {
                    remoteSessionStartByTrack[id] = latestSessionStart
                }
            },
            mergeCoordinates = { geometryCoords, responseCoords ->
                TrackerMapCoordinateMergePolicy.mergedCoordinates(
                    geometryCoords = geometryCoords,
                    responseCoords = responseCoords
                )
            },
            mapCoordinatesToTrail = { merged -> mapCoordinatesToTrail(merged) }
        )
    }

    private fun resolveGroupModeSelection(state: TrackerMapUiState): TrackerMapGroupModeSelection {
        if (state.mode != TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
            return TrackerMapGroupModeSelection(groupId = null, trackerIds = emptySet())
        }
        val visibility = trackerManagementStateStore.mapVisibility.value
        val hiddenGroupIds = visibility?.hidden_group_ids.orEmpty().toSet()
        val hiddenTrackIds = visibility?.hidden_track_ids.orEmpty().toSet()
        val hiddenOwnerTrackerIds = trackerManagementStateStore.trackers.value
            .filter { it.isOwner() && it.settingBoolean("hidden") == true }
            .map { it.id.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val preferredTrackerId = effectiveDisplayedTrackerId(state).ifBlank { state.runtime.selectedTrackerId }
        return TrackerMapGroupModePolicy.resolveSelection(
            groups = trackerManagementStateStore.groups.value,
            hiddenGroupIds = hiddenGroupIds,
            hiddenTrackIds = hiddenTrackIds,
            hiddenOwnerTrackerIds = hiddenOwnerTrackerIds,
            preferredGroupId = state.currentGroupId,
            preferredTrackerId = preferredTrackerId
        )
    }

    private fun resolveGroupModeOptions(): List<TrackerMapGroupModeOption> {
        val visibility = trackerManagementStateStore.mapVisibility.value
        val hiddenGroupIds = visibility?.hidden_group_ids.orEmpty().toSet()
        val hiddenTrackIds = visibility?.hidden_track_ids.orEmpty().toSet()
        val hiddenOwnerTrackerIds = trackerManagementStateStore.trackers.value
            .filter { it.isOwner() && it.settingBoolean("hidden") == true }
            .map { it.id.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        return TrackerMapGroupModePolicy.resolveEligibleGroups(
            groups = trackerManagementStateStore.groups.value,
            hiddenGroupIds = hiddenGroupIds,
            hiddenTrackIds = hiddenTrackIds,
            hiddenOwnerTrackerIds = hiddenOwnerTrackerIds
        )
    }

    private fun mapCoordinatesToTrail(coordinates: List<List<Double>>): List<QueuedLocation> {
        if (coordinates.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val start = now - coordinates.size
        return coordinates.mapIndexedNotNull { index, point ->
            val lon = point.getOrNull(0) ?: return@mapIndexedNotNull null
            val lat = point.getOrNull(1) ?: return@mapIndexedNotNull null
            QueuedLocation(
                id = -(index + 1L),
                time = start + index,
                latitude = lat,
                longitude = lon,
                altitude = null,
                speed = null,
                bearing = null,
                accuracy = null,
                sat = null,
                prov = "server_geometry",
                dist = null
            )
        }.takeLast(TRAIL_POINT_LIMIT)
    }

    private fun handleTrackPointEvent(point: TrackPointEvent) {
        val state = _uiState.value
        val tracker = if (point.source == TrackPointSource.REMOTE_STREAM) {
            trackerManagementStateStore.trackers.value.firstOrNull { it.id == point.trackId }
        } else {
            null
        }
        val reduction = TrackerMapPointEventReducer.reduce(
            TrackerMapPointReductionInput(
                state = state,
                point = point,
                recentDataWindow = tracker?.settingString("recent_data_window"),
                currentSessionStartMs = remoteSessionStartByTrack[point.trackId],
                pendingReopenTrackerId = pendingReopenSingleTrackerLoadId,
                sessionAnchorTrackerId = sessionAnchorResyncTrackerId,
                sessionAnchorUntilElapsedMs = sessionAnchorResyncUntilElapsedMs,
                nowElapsedMs = SystemClock.elapsedRealtime(),
                trailPointLimit = TRAIL_POINT_LIMIT
            )
        )
        sessionAnchorResyncTrackerId = reduction.nextSessionAnchorTrackerId
        sessionAnchorResyncUntilElapsedMs = reduction.nextSessionAnchorUntilElapsedMs
        reduction.nextSessionStartMs?.let { remoteSessionStartByTrack[point.trackId] = it }
        if (reduction.shouldUpdateUiState) {
            _uiState.value = reduction.nextState
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
        val command = TrackerMapStreamingCoordinator.resolve(
            TrackerMapStreamingDecisionInput(
                mode = state.mode,
                streamTargetIds = state.streamTargetIds,
                displayedTrackerId = effectiveDisplayedId,
                displayedTrackerName = effectiveDisplayedName,
                selectedTrackerId = state.runtime.selectedTrackerId
            )
        )
        when (command) {
            is TrackerMapStreamingCommand.Start -> {
                MapStreamingServiceHelper.startStreaming(
                    context = appContext,
                    trackerIds = command.trackerIds,
                    trackerName = command.trackerName
                )
            }
            TrackerMapStreamingCommand.Stop -> {
                MapStreamingServiceHelper.stopStreaming(appContext)
            }
            TrackerMapStreamingCommand.NoOp -> Unit
        }
    }

    private fun effectiveDisplayedTrackerId(state: TrackerMapUiState): String {
        return state.displayedTrackerId.trim().ifBlank { state.runtime.selectedTrackerId.trim() }
    }

    private fun effectiveDisplayedTrackerName(state: TrackerMapUiState): String {
        return state.displayedTrackerName.trim().ifBlank { state.runtime.selectedTrackerName.trim() }
    }

    private fun Tracker.settingString(key: String): String? {
        val raw = settings?.get(key) ?: return null
        return when (raw) {
            is String -> raw
            else -> raw.toString()
        }
    }

    private fun Tracker.settingBoolean(key: String): Boolean? {
        return when (val raw = settings?.get(key)) {
            is Boolean -> raw
            is String -> raw.toBooleanStrictOrNull()
            else -> null
        }
    }

    private fun primeSessionAnchorResync(trackerId: String) {
        val normalizedId = trackerId.trim()
        if (normalizedId.isEmpty()) return
        remoteSessionStartByTrack.remove(normalizedId)
        sessionAnchorResyncTrackerId = normalizedId
        sessionAnchorResyncUntilElapsedMs = SystemClock.elapsedRealtime() + SESSION_ANCHOR_RESYNC_MS
    }

    private fun clearSessionAnchorResync(trackerId: String) {
        if (sessionAnchorResyncTrackerId == trackerId.trim()) {
            sessionAnchorResyncTrackerId = null
            sessionAnchorResyncUntilElapsedMs = 0L
        }
    }

    override fun onCleared() {
        pointEventChannel.close()
        fitTrailSignal.close()
        super.onCleared()
    }
}
