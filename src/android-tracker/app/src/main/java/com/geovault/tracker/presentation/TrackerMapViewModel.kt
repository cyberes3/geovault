package com.geovault.tracker.presentation

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.common.maps.core.OutlinedGeoJsonLineLayers
import com.geovault.tracker.TrackingService
import com.geovault.tracker.Tracker
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.defaultTrackerColorHex
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
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
    val streamingStatus: TrackerMapStreamingStatusUiModel = TrackerMapStreamingStatusUiModel(),
    val currentGroupId: String = "",
    val groupModeOptions: List<TrackerMapGroupModeOption> = emptyList(),
    val displayedTrackerId: String = "",
    val displayedTrackerName: String = "",
    val isBottomCardVisible: Boolean = false,
    val selectedMapTracker: TrackerMapSelectionCard? = null,
    val selectionLockTrackerId: String = "",
    val mode: TrackerMapDisplayMode = TrackerMapDisplayMode.SINGLE_SESSION,
    val followLockEnabled: Boolean = false,
    val liveActiveFitEnabled: Boolean = false,
    val isGeometryLoading: Boolean = false,
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

private fun TrackerMapUiState.withAllMapLocksDisabled(): TrackerMapUiState = copy(
    followLockEnabled = false,
    liveActiveFitEnabled = false,
    selectionLockTrackerId = "",
)

private fun TrackerMapUiState.withClearedMapSelectionCard(): TrackerMapUiState = copy(
    isBottomCardVisible = false,
    selectedMapTracker = null,
    selectionLockTrackerId = "",
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

        @JvmStatic
        internal fun shouldReloadForRecentDataWindowChange(
            oldWindow: String?,
            newWindow: String?,
            mode: TrackerMapDisplayMode,
            selectedTrackerId: String,
            displayedTrackerId: String,
            runtimeRunning: Boolean,
            activeStreamedTrackerIds: Set<String>,
            changedTrackerId: String,
        ): Boolean {
            if (oldWindow == null || oldWindow == newWindow) return false
            if (mode != TrackerMapDisplayMode.SINGLE_SESSION) return false
            val selected = selectedTrackerId.trim()
            val displayed = displayedTrackerId.trim()
            val changed = changedTrackerId.trim()
            if (changed.isEmpty()) return false
            // Filter-driven reloads should only target the selected tracker.
            if (selected.isEmpty() || changed != selected) return false
            if (displayed.isNotEmpty() && changed != displayed) return false
            if (runtimeRunning) return false
            return changed !in activeStreamedTrackerIds
        }

        @JvmStatic
        internal fun resolveBottomCardVisibilityForMarkerTap(
            hasSelectionCard: Boolean
        ): Boolean {
            return hasSelectionCard
        }

        @JvmStatic
        internal fun resolveBackgroundTapShouldCloseBottomCard(
            isBottomCardVisible: Boolean,
            hasSelectionCard: Boolean
        ): Boolean {
            return isBottomCardVisible || hasSelectionCard
        }

        @JvmStatic
        internal fun resolveRenderSelectedMapTrackerId(
            isBottomCardVisible: Boolean,
            selectedMapTrackerId: String?
        ): String? {
            return selectedMapTrackerId
                ?.trim()
                ?.takeIf { isBottomCardVisible && it.isNotEmpty() }
        }

        @JvmStatic
        internal fun resolveFocusActionVisible(mode: TrackerMapDisplayMode): Boolean {
            return mode != TrackerMapDisplayMode.SINGLE_SESSION
        }
    }

    private val appContext = application.applicationContext
    private val streamingReconciler = LiveTrackStreamingReconciler(appContext)
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
    private var lastBackgroundAtElapsedMs: Long = 0L
    private var mapReady: Boolean = false
    private var pendingResumeEvaluation: Boolean = false
    private var mapSurfaceVisible: Boolean = false
    private var pendingInitialTrackerForMap: Boolean = false
    private var runtimeTrailReloadJob: Job? = null
    private var runtimeTrailReloadPending: Boolean = false
    private var lastTrailLoadSeed: String? = null
    private var pendingReopenSingleTrackerLoadId: String? = null
    private var pendingFitAfterReload: Boolean = false
    private val recentDataWindowByTracker = mutableMapOf<String, String?>()
    private var lastObservedTrackingRunning: Boolean? = null
    private var lastObservedStreamingRunning: Boolean = false
    private val runtimeResyncPolicy = TrackerMapRuntimeResyncPolicy()
    private val reopenOrchestrator = TrackerMapReopenOrchestrator()
    private val sessionRequestDeduper = TrackerMapSessionRequestDeduper()
    private val geometryLoadingTracker = TrackerMapGeometryLoadingTracker(
        onLoadingChanged = ::setGeometryLoading
    )

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
                if (runtimeResyncDecision.restartDisplayedStreaming) {
                    streamingReconciler.invalidateDedupe()
                    reconcileStreaming(_uiState.value)
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
                val wasRunning = lastObservedStreamingRunning
                lastObservedStreamingRunning = snapshot.isRunning
                val current = _uiState.value
                _uiState.value = current.copy(
                    activeStreamedTrackerIds = snapshot.activeTrackerIds,
                    remoteLastPoints = if (snapshot.isRunning) {
                        current.remoteLastPoints.filterKeys { it in snapshot.activeTrackerIds }
                    } else {
                        emptyMap()
                    },
                    streamingStatus = TrackerMapStreamingStatusPolicy.resolve(
                        snapshot = snapshot,
                        streamTargetIds = current.streamTargetIds,
                    ),
                )
                if (wasRunning && !snapshot.isRunning &&
                    snapshot.lifecycleState == TrackingLifecycleState.STOPPED
                ) {
                    restoreSelectedTrackerAfterStreamingStop()
                }
            }
        }
        viewModelScope.launch {
            combine(
                trackerManagementStateStore.trackers,
                trackerManagementStateStore.groups,
                trackerManagementStateStore.mapVisibility
            ) { trackers, groups, visibility ->
                val trackerFingerprint = trackers.joinToString(separator = "|") { tracker ->
                    "${tracker.id}:${tracker.updated_at ?: 0L}:${tracker.geometry?.coordinates?.size ?: 0}"
                }
                val groupFingerprint = groups.joinToString(separator = "|") { group ->
                    "${group.id}:${group.updated_at ?: 0L}:${group.track_ids?.size ?: 0}"
                }
                val visibilityFingerprint = if (visibility == null) {
                    "none"
                } else {
                    "${visibility.hidden_group_ids.orEmpty().sorted()}|${visibility.hidden_track_ids.orEmpty().sorted()}"
                }
                "$trackerFingerprint#$groupFingerprint#$visibilityFingerprint"
            }.distinctUntilChanged().collectLatest {
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
                        if (shouldReloadForRecentDataWindowChange(
                                oldWindow = oldWindow,
                                newWindow = newWindow,
                                mode = state.mode,
                                selectedTrackerId = state.runtime.selectedTrackerId,
                                displayedTrackerId = effectiveDisplayedTrackerId(state),
                                runtimeRunning = state.runtime.isRunning,
                                activeStreamedTrackerIds = state.activeStreamedTrackerIds,
                                changedTrackerId = trackerId,
                            )
                        ) {
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
                reconcileStreaming(state)
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
        val nextState = _uiState.value.copy(
            mode = mode,
            currentGroupId = preferredGroupId,
            groupModeOptions = groupOptions,
        )
        val pendingReopenTrackerId = if (mode == TrackerMapDisplayMode.SINGLE_SESSION) {
            pendingReopenSingleTrackerLoadId
        } else {
            null
        }
        applyMapContextTransition(
            nextState = nextState,
            pendingReopenTrackerId = pendingReopenTrackerId
        )
    }

    fun setGroupModeGroup(groupId: String) {
        val normalized = groupId.trim()
        if (normalized.isEmpty()) return
        val state = _uiState.value
        if (state.currentGroupId == normalized && state.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
            return
        }
        val nextState = state.copy(
            currentGroupId = normalized,
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
        )
        applyMapContextTransition(
            nextState = nextState,
            pendingReopenTrackerId = null
        )
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
        val nextState = state.copy(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = normalizedId,
            displayedTrackerName = resolvedName,
            currentGroupId = "",
            groupModeOptions = emptyList(),
        )
        applyMapContextTransition(
            nextState = nextState,
            pendingReopenTrackerId = normalizedId
        )
    }

    fun openGroupOnMap(groupId: String) {
        val normalizedId = groupId.trim()
        if (normalizedId.isEmpty()) return
        val groupOptions = resolveGroupModeOptions()
        val resolvedGroupId = normalizedId.takeIf { candidate ->
            groupOptions.any { it.groupId == candidate }
        } ?: groupOptions.firstOrNull()?.groupId.orEmpty()
        val nextState = _uiState.value.copy(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            currentGroupId = resolvedGroupId,
            groupModeOptions = groupOptions,
        )
        applyMapContextTransition(
            nextState = nextState,
            pendingReopenTrackerId = null
        )
    }

    fun restoreSelectedTrackerAfterStreamingStop() {
        val state = _uiState.value
        if (state.runtime.isRunning) return
        restoreSelectedTrackerMapContext()
    }

    fun restoreSelectedTrackerMapContext() {
        val state = _uiState.value
        val selectedId = state.runtime.selectedTrackerId.trim()
        streamingReconciler.stopForegroundStreaming()
        val nextState = state.copy(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = selectedId,
            displayedTrackerName = if (selectedId.isNotEmpty()) {
                state.runtime.selectedTrackerName
            } else {
                ""
            },
            currentGroupId = "",
            groupModeOptions = emptyList(),
        )
        applyMapContextTransition(
            nextState = nextState,
            pendingReopenTrackerId = selectedId.ifEmpty { null }
        )
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

    fun onTrackerMarkerTapped(trackerId: String) {
        val normalizedTrackerId = trackerId.trim()
        if (normalizedTrackerId.isEmpty()) return
        val state = _uiState.value
        val selection = buildSelectionCard(state, normalizedTrackerId)
        if (selection == null) {
            _uiState.value = state.withClearedMapSelectionCard()
            return
        }
        _uiState.value = stateWithSelectionCard(state, selection)
    }

    fun onMapBackgroundTapped(): Boolean {
        val state = _uiState.value
        if (!resolveBackgroundTapShouldCloseBottomCard(
                isBottomCardVisible = state.isBottomCardVisible,
                hasSelectionCard = state.selectedMapTracker != null
            )
        ) {
            return false
        }
        _uiState.value = state.withClearedMapSelectionCard()
        return true
    }

    fun selectMapTrackerFromTap(trackerId: String) {
        onTrackerMarkerTapped(trackerId)
    }

    fun clearMapTrackerSelection() {
        onMapBackgroundTapped()
    }

    fun focusSelectedTrackerOnMap() {
        val state = _uiState.value
        val selection = state.selectedMapTracker ?: return
        openTrackerOnMap(selection.trackerId, selection.trackerName)
    }

    fun toggleSelectedTrackerLock() {
        val state = _uiState.value
        val selection = state.selectedMapTracker ?: return
        toggleTrackerLock(selection.trackerId)
    }

    fun toggleDisplayedTrackerLock() {
        val state = _uiState.value
        val displayedId = effectiveDisplayedTrackerId(state)
        if (displayedId.isEmpty()) return
        toggleTrackerLock(displayedId)
    }

    private fun toggleTrackerLock(trackerId: String) {
        val selectedId = trackerId.trim()
        if (selectedId.isEmpty()) return
        val state = _uiState.value
        val nextSelectionLock = if (state.selectionLockTrackerId == selectedId) "" else selectedId
        _uiState.value = state.withAllMapLocksDisabled().copy(selectionLockTrackerId = nextSelectionLock)
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
        val multiTrailPoint = state.allQueueTrailsByTracker[normalizedId]?.lastOrNull()
        val trackerLastPoint = tracker?.last_point
        val latitude = when {
            remotePoint != null -> remotePoint.lat
            singleTrailPoint != null -> singleTrailPoint.latitude
            multiTrailPoint != null -> multiTrailPoint.latitude
            trackerLastPoint != null && trackerLastPoint.size >= 2 -> trackerLastPoint[1]
            else -> return null
        }
        val longitude = when {
            remotePoint != null -> remotePoint.lon
            singleTrailPoint != null -> singleTrailPoint.longitude
            multiTrailPoint != null -> multiTrailPoint.longitude
            trackerLastPoint != null && trackerLastPoint.size >= 2 -> trackerLastPoint[0]
            else -> return null
        }
        return ResolvedTrackerPoint(
            latitude = latitude,
            longitude = longitude,
            lastUpdatedMs = remotePoint?.timestampMs ?: singleTrailPoint?.time ?: multiTrailPoint?.time ?: tracker?.updated_at,
            accuracyMeters = remotePoint?.accuracyMeters ?: singleTrailPoint?.accuracy ?: multiTrailPoint?.accuracy,
        )
    }

    private fun stateWithSelectionCard(
        state: TrackerMapUiState,
        selection: TrackerMapSelectionCard
    ): TrackerMapUiState {
        val nextSelectionLockId = state.selectionLockTrackerId.trim()
            .takeIf { it.isNotEmpty() && it == selection.trackerId }
            .orEmpty()
        return state.copy(
            isBottomCardVisible = resolveBottomCardVisibilityForMarkerTap(hasSelectionCard = true),
            selectedMapTracker = selection,
            selectionLockTrackerId = nextSelectionLockId,
        )
    }

    private fun stateWithClearedRenderedTrails(state: TrackerMapUiState): TrackerMapUiState {
        return state.copy(
            trail = emptyList(),
            allQueueTrailsByTracker = emptyMap(),
        )
    }

    private fun stateWithResetMapContext(state: TrackerMapUiState): TrackerMapUiState {
        return stateWithClearedRenderedTrails(state)
            .withAllMapLocksDisabled()
            .withClearedMapSelectionCard()
    }

    private fun applyMapContextTransition(
        nextState: TrackerMapUiState,
        pendingReopenTrackerId: String?
    ) {
        _uiState.value = stateWithResetMapContext(nextState)
        pendingReopenSingleTrackerLoadId = pendingReopenTrackerId
        pendingFitAfterReload = true
        lastTrailLoadSeed = null
        requestRuntimeTrailReload()
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
            streamingReconciler.invalidateDedupe()
            reconcileStreaming(_uiState.value)
        }
    }

    fun onMapSurfaceHidden() {
        mapSurfaceVisible = false
        mapReady = false
        lastBackgroundAtElapsedMs = SystemClock.elapsedRealtime()
        streamingReconciler.invalidateDedupe()
        viewModelScope.launch {
            sessionRequestDeduper.clear()
        }
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
            streamingReconciler.invalidateDedupe()
            reconcileStreaming(_uiState.value)
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
                streamingReconciler.invalidateDedupe()
                reconcileStreaming(_uiState.value)
            }
            TrackerMapResumeDecision.ClearSingleTrackerState -> {
                pendingReopenSingleTrackerLoadId = null
                _uiState.value = _uiState.value.copy(
                    displayedTrackerId = "",
                    displayedTrackerName = "",
                    remoteLastPoints = emptyMap(),
                    streamTargetIds = emptySet()
                )
                _uiState.value = _uiState.value.withClearedMapSelectionCard()
                streamingReconciler.stopForegroundStreaming()
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
                        displayedTrackerName = trackerName,
                    )
                    _uiState.value = _uiState.value.withClearedMapSelectionCard()
                }
                reloadTrailFromDatabase(force = true)
                if (pendingReopenSingleTrackerLoadId == trackerId) {
                    pendingReopenSingleTrackerLoadId = null
                }
                streamingReconciler.invalidateDedupe()
                reconcileStreaming(_uiState.value)
            }
            TrackerMapResumeDecision.RestartDisplayedTrackerStreaming -> {
                pendingReopenSingleTrackerLoadId = null
                streamingReconciler.invalidateDedupe()
                reconcileStreaming(_uiState.value)
            }
        }
    }

    fun setFollowLock(enabled: Boolean) {
        val state = _uiState.value
        _uiState.value = if (enabled) {
            state.withAllMapLocksDisabled().copy(followLockEnabled = true)
        } else {
            state.copy(followLockEnabled = false)
        }
    }

    fun disableAllMapLocks() {
        val state = _uiState.value
        if (!state.followLockEnabled && !state.liveActiveFitEnabled && state.selectionLockTrackerId.isEmpty()) {
            return
        }
        _uiState.value = state.withAllMapLocksDisabled()
    }

    fun setLiveActiveFit(enabled: Boolean) {
        val state = _uiState.value
        _uiState.value = if (enabled) {
            state.withAllMapLocksDisabled().copy(liveActiveFitEnabled = true)
        } else {
            state.copy(liveActiveFitEnabled = false)
        }
        if (enabled) {
            requestFitTrail()
        }
    }

    fun requestFitTrail() {
        fitTrailSignal.trySend(Unit)
    }

    fun buildMapRenderState(): com.geovault.common.maps.render.MapRenderState {
        val s = _uiState.value
        val trackerColors = trackerManagementStateStore.trackers.value.associate { it.id to (it.color ?: "") }
        val trackerDisplayNames = trackerManagementStateStore.trackers.value.associate { it.id to it.name }
        val trackerRenderOrder = trackerManagementStateStore.trackers.value.map { it.id }
        val effectiveDisplayedId = effectiveDisplayedTrackerId(s)
        val streamedAccuracyByTrackerId = buildStreamedAccuracyByTrackerId(s, effectiveDisplayedId)
        val fallbackAccuracyByTrackerId = buildFallbackAccuracyByTrackerId(s)
        val visibleTrackerIds = resolveVisibleAccuracyTrackerIds(s, effectiveDisplayedId)
        val allowAccuracyFallbackByTrackerId = TrackerAccuracyFallbackPolicy.resolveAllowedFallbackTrackerIds(
            TrackerAccuracyFallbackPolicyInput(
                mode = s.mode,
                runtimeRunning = s.runtime.isRunning,
                selectedTrackerId = s.runtime.selectedTrackerId,
                displayedTrackerId = effectiveDisplayedId,
                visibleTrackerIds = visibleTrackerIds,
            )
        )
        return TrackerMapStateTransforms.buildRenderState(
            mode = s.mode,
            trail = s.trail,
            runtime = s.runtime,
            trailOutlineColorHex = OutlinedGeoJsonLineLayers.borderColorHex(appContext),
            remoteLastPoints = s.remoteLastPoints,
            activeStreamedTrackerIds = s.activeStreamedTrackerIds,
            allQueueTrailsByTracker = s.allQueueTrailsByTracker,
            trackerColorById = trackerColors,
            trackerDisplayNameById = trackerDisplayNames,
            displayedTrackerId = effectiveDisplayedId,
            selectedMapTrackerId = resolveRenderSelectedMapTrackerId(
                isBottomCardVisible = s.isBottomCardVisible,
                selectedMapTrackerId = s.selectedMapTracker?.trackerId
            ),
            trackerRenderOrder = trackerRenderOrder,
            streamedAccuracyMeters = null,
            fallbackAccuracyMeters = null,
            allowAccuracyFallback = false,
            streamedAccuracyByTrackerId = streamedAccuracyByTrackerId,
            fallbackAccuracyByTrackerId = fallbackAccuracyByTrackerId,
            allowAccuracyFallbackByTrackerId = allowAccuracyFallbackByTrackerId,
            defaultIconColorHex = defaultTrackerColorHex(appContext),
        )
    }

    fun trailBoundsOrNull(): LatLngBounds? {
        val s = _uiState.value
        if (s.mode == TrackerMapDisplayMode.ALL_QUEUE || s.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
            if (s.liveActiveFitEnabled) {
                val trackers = trackerManagementStateStore.trackers.value
                val activeBounds = TrackerMapLiveActiveFitPolicy.activeTrailBounds(
                    allQueueTrailsByTracker = s.allQueueTrailsByTracker,
                    remoteLastPoints = s.remoteLastPoints,
                    trackers = trackers,
                    nowMs = System.currentTimeMillis(),
                )
                if (activeBounds != null) return activeBounds
            }
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

    private fun buildStreamedAccuracyByTrackerId(
        state: TrackerMapUiState,
        effectiveDisplayedId: String
    ): Map<String, Float> {
        val accuracyByTracker = mutableMapOf<String, Float>()
        val displayedId = effectiveDisplayedId.trim()
        state.trail.lastOrNull()?.accuracy?.toFinitePositiveOrNull()?.let { accuracy ->
            if (displayedId.isNotEmpty()) {
                accuracyByTracker[displayedId] = accuracy
            }
        }
        state.allQueueTrailsByTracker.forEach { (trackerId, queueTrail) ->
            queueTrail.lastOrNull()?.accuracy?.toFinitePositiveOrNull()?.let { accuracy ->
                val normalizedId = trackerId.trim()
                if (normalizedId.isNotEmpty()) {
                    accuracyByTracker[normalizedId] = accuracy
                }
            }
        }
        state.remoteLastPoints.forEach { (trackerId, remotePoint) ->
            remotePoint.accuracyMeters?.toFinitePositiveOrNull()?.let { accuracy ->
                val normalizedId = trackerId.trim()
                if (normalizedId.isNotEmpty()) {
                    accuracyByTracker[normalizedId] = accuracy
                }
            }
        }
        return accuracyByTracker
    }

    private fun buildFallbackAccuracyByTrackerId(state: TrackerMapUiState): Map<String, Float> {
        val fallbackByTrackerId = mutableMapOf<String, Float>()
        trackerManagementStateStore.trackers.value.forEach { tracker ->
            val trackerId = tracker.id.trim()
            if (trackerId.isEmpty()) return@forEach
            extractTrackerLatestAccuracyMeters(tracker)?.toFinitePositiveOrNull()?.let { accuracy ->
                fallbackByTrackerId[trackerId] = accuracy
            }
        }
        val selectedTrackerId = state.runtime.selectedTrackerId.trim()
        state.runtime.lastAccuracyMeters.toFinitePositiveOrNull()?.let { runtimeAccuracy ->
            if (selectedTrackerId.isNotEmpty()) {
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

    private fun refreshStreamTargets() {
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
        if (seed == lastStreamTargetsSeed) return
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

    private suspend fun reloadTrailFromDatabase(force: Boolean = false) {
        val state = _uiState.value
        val activeTrackerId = effectiveDisplayedTrackerId(state)
        val guardInput = TrailReloadGuardInput(
            force = force,
            mode = state.mode,
            trailSize = state.trail.size,
            runtimeRunning = state.runtime.isRunning,
            activeStreamedTrackerIds = state.activeStreamedTrackerIds,
            displayedTrackerId = activeTrackerId,
        )
        if (!TrackerMapTrailReloadGuardPolicy.shouldProceed(guardInput)) return
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
        var workingState = state
        preloadedSingleTrackerTrailFromCacheOrNull(
            mode = workingState.mode,
            activeTrackerId = activeTrackerId
        )?.let { preloadedTrail ->
            if (workingState.trail.isEmpty()) {
                workingState = workingState.copy(
                    trail = preloadedTrail,
                    allQueueTrailsByTracker = emptyMap(),
                )
                _uiState.value = workingState
            }
        }
        val plan = TrackerMapTrailReloadCoordinator.resolvePlan(
            TrackerMapTrailReloadInput(
                mode = workingState.mode,
                runtimeRunning = workingState.runtime.isRunning,
                activeTrackerId = activeTrackerId,
                rosterTrackerIds = rosterTrackerIds,
                groupSelection = groupSelection
            )
        )
        val existingTrailMinTimeMs = workingState.trail.minOfOrNull { it.time }
        val existingMultiMinTimes = workingState.allQueueTrailsByTracker
            .mapValues { (_, pts) -> pts.minOfOrNull { it.time } }
            .filterValues { it != null }
            .mapValues { it.value!! }
        val (trail, allQueueTrailsByTracker) = when (plan.source) {
            TrackerMapTrailSource.SINGLE_SERVER -> {
                loadSingleTrackerTrailFromServer(plan.singleTrackerId, existingTrailMinTimeMs) to emptyMap()
            }
            TrackerMapTrailSource.MULTI_SERVER -> {
                val multiTrails = loadTrailsForTrackerIds(plan.trackerIds, existingMultiMinTimes).toMutableMap()
                plan.overlayTrackerId?.let { overlayTrackerId ->
                    multiTrails[overlayTrackerId] = loadQueueTrailWithOverlay()
                }
                val fallbackTrail = multiTrails[plan.activeTrackerId].orEmpty()
                fallbackTrail to multiTrails.toMap()
            }
            TrackerMapTrailSource.SINGLE_QUEUE -> {
                loadQueueTrailWithOverlay() to emptyMap()
            }
        }
        if (trailSeedForState(_uiState.value) != seed) {
            lastTrailLoadSeed = null
            return
        }
        val currentState = _uiState.value
        val mergedTrail = mergeStreamingOverlayIntoReloadedTrail(
            reloadedTrail = trail,
            currentTrail = currentState.trail,
        )
        val mergedMultiTrails = mergeStreamingOverlayIntoReloadedMultiTrails(
            reloadedTrails = allQueueTrailsByTracker,
            currentTrails = currentState.allQueueTrailsByTracker,
        )
        _uiState.value = currentState.copy(
            trail = mergedTrail,
            allQueueTrailsByTracker = mergedMultiTrails,
            currentGroupId = if (workingState.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
                plan.resolvedGroupId
            } else {
                workingState.currentGroupId
            },
            groupModeOptions = if (workingState.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
                resolveGroupModeOptions()
            } else {
                emptyList()
            },
        )
        if (pendingFitAfterReload && (mergedTrail.isNotEmpty() || mergedMultiTrails.isNotEmpty())) {
            pendingFitAfterReload = false
            requestFitTrail()
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

    private fun mergeStreamingOverlayIntoReloadedTrail(
        reloadedTrail: List<QueuedLocation>,
        currentTrail: List<QueuedLocation>,
    ): List<QueuedLocation> {
        if (currentTrail.isEmpty()) return reloadedTrail
        if (reloadedTrail.isEmpty()) return currentTrail
        val latestReloadedTime = reloadedTrail.maxOfOrNull { it.time } ?: 0L
        val survivingOverlay = currentTrail.filter { it.time > latestReloadedTime }
        if (survivingOverlay.isEmpty()) return reloadedTrail
        return (reloadedTrail + survivingOverlay)
            .sortedBy { it.time }
            .takeLast(TRAIL_POINT_LIMIT)
    }

    private fun mergeStreamingOverlayIntoReloadedMultiTrails(
        reloadedTrails: Map<String, List<QueuedLocation>>,
        currentTrails: Map<String, List<QueuedLocation>>,
    ): Map<String, List<QueuedLocation>> {
        if (currentTrails.isEmpty()) return reloadedTrails
        if (reloadedTrails.isEmpty()) return currentTrails
        return reloadedTrails.keys.associateWith { trackerId ->
            mergeStreamingOverlayIntoReloadedTrail(
                reloadedTrail = reloadedTrails.getValue(trackerId),
                currentTrail = currentTrails[trackerId].orEmpty(),
            )
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

    private suspend fun loadSingleTrackerTrailFromServer(
        trackerId: String,
        existingTrailMinTimeMs: Long?,
    ): List<QueuedLocation> {
        return TrackerMapTrailDataCoordinator.loadSingleTrackerTrail(
            trackerId = trackerId,
            existingTrailMinTimeMs = existingTrailMinTimeMs,
            loadTrackerGeometry = { id ->
                sessionRequestDeduper.loadOnce("single:geometry:$id") {
                    geometryLoadingTracker.track { trackerManagementRepository.loadTrackerGeometry(id) }
                }
            },
            loadQueueTrailWithOverlay = { loadQueueTrailWithOverlay() },
            mapCoordinatesToTrail = { id, merged, pointParams, minTime ->
                mapCoordinatesToTrail(id, merged, pointParams, minTime)
            }
        )
    }

    private suspend fun loadTrailsForTrackerIds(
        trackerIds: Collection<String>,
        existingTrailMinTimeMsByTracker: Map<String, Long>,
    ): Map<String, List<QueuedLocation>> {
        return TrackerMapTrailDataCoordinator.loadTrailsForTrackerIds(
            trackerIds = trackerIds,
            existingTrailMinTimeMsByTracker = existingTrailMinTimeMsByTracker,
            loadTrackersGeometry = { ids ->
                val normalizedIds = ids.map { it.trim() }.filter { it.isNotEmpty() }.sorted()
                val key = "multi:geometry:${normalizedIds.joinToString(",")}"
                sessionRequestDeduper.loadOnce(key) {
                    geometryLoadingTracker.track { trackerManagementRepository.loadTrackersGeometry(ids) }
                }
            },
            mapCoordinatesToTrail = { id, merged, pointParams, minTime ->
                mapCoordinatesToTrail(id, merged, pointParams, minTime)
            }
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

    private fun mapCoordinatesToTrail(
        trackerId: String,
        coordinates: List<List<Double>>,
        pointParams: List<Map<String, Any?>>? = null,
        existingTrailMinTimeMs: Long? = null,
    ): List<QueuedLocation> {
        if (coordinates.isEmpty()) return emptyList()
        val normalizedTrackerId = trackerId.trim()
        if (normalizedTrackerId.isEmpty()) return emptyList()
        val latestAccuracyMeters = pointParams
            ?.lastOrNull()
            ?.get("acc")
            ?.let { raw ->
                when (raw) {
                    is Number -> raw.toFloat()
                    is String -> raw.toFloatOrNull()
                    else -> null
                }
            }
            ?.takeIf { it.isFinite() && it > 0f }
        val timestamps = resolveGeometryTimestamps(coordinates, existingTrailMinTimeMs)
        return coordinates.mapIndexedNotNull { index, point ->
            val lon = point.getOrNull(0) ?: return@mapIndexedNotNull null
            val lat = point.getOrNull(1) ?: return@mapIndexedNotNull null
            QueuedLocation(
                id = -(index + 1L),
                trackerId = normalizedTrackerId,
                time = timestamps[index],
                latitude = lat,
                longitude = lon,
                altitude = null,
                speed = null,
                bearing = null,
                accuracy = if (index == coordinates.lastIndex) latestAccuracyMeters else null,
                sat = null,
                prov = "server_geometry",
                dist = null
            )
        }.takeLast(TRAIL_POINT_LIMIT)
    }

    private fun resolveGeometryTimestamps(
        coordinates: List<List<Double>>,
        existingTrailMinTimeMs: Long? = null,
    ): List<Long> {
        val parsed = coordinates.map { coord ->
            val raw = coord.getOrNull(2)?.toLong() ?: return@map null
            TrackerMapSessionWindowPolicy.normalizeTimestampToMs(raw)
        }
        val hasRealTimestamps = parsed.any { it != null }
        if (hasRealTimestamps) {
            val fallbackBase = parsed.filterNotNull().maxOrNull() ?: 0L
            return parsed.mapIndexed { index, ts -> ts ?: (fallbackBase + index + 1) }
        }
        val anchor = existingTrailMinTimeMs ?: System.currentTimeMillis()
        val fallbackStart = anchor - coordinates.size - 1L
        return coordinates.indices.map { idx -> (fallbackStart + idx).coerceAtLeast(0L) }
    }

    private fun preloadedSingleTrackerTrailFromCacheOrNull(
        mode: TrackerMapDisplayMode,
        activeTrackerId: String
    ): List<QueuedLocation>? {
        if (mode != TrackerMapDisplayMode.SINGLE_SESSION) return null
        val trackerId = activeTrackerId.trim()
        if (trackerId.isEmpty()) return null
        val cachedTracker = trackerManagementStateStore.trackers.value
            .firstOrNull { it.id == trackerId }
            ?: return null
        val cachedGeometry = cachedTracker.geometry?.coordinates.orEmpty()
        if (cachedGeometry.isEmpty()) return null
        return mapCoordinatesToTrail(trackerId, cachedGeometry).takeIf { it.isNotEmpty() }
    }

    private fun handleTrackPointEvent(point: TrackPointEvent) {
        val state = _uiState.value
        val reduction = TrackerMapPointEventReducer.reduce(
            TrackerMapPointReductionInput(
                state = state,
                point = point,
                trailPointLimit = TRAIL_POINT_LIMIT
            )
        )
        if (reduction.shouldUpdateUiState) {
            _uiState.value = reduction.nextState
            if (reduction.nextState.liveActiveFitEnabled &&
                point.source == TrackPointSource.REMOTE_STREAM
            ) {
                requestFitTrail()
            }
        }
    }

    private fun reconcileStreaming(state: TrackerMapUiState) {
        streamingReconciler.reconcile(
            state,
            effectiveDisplayedTrackerId(state),
            effectiveDisplayedTrackerName(state),
        )
    }

    private fun effectiveDisplayedTrackerId(state: TrackerMapUiState): String {
        return state.displayedTrackerId.trim().ifBlank { state.runtime.selectedTrackerId.trim() }
    }

    private fun effectiveDisplayedTrackerName(state: TrackerMapUiState): String {
        return state.displayedTrackerName.trim().ifBlank { state.runtime.selectedTrackerName.trim() }
    }

    private fun trailSeedForState(state: TrackerMapUiState): String {
        return TrackerMapReloadSeedPolicy.trailSeed(
            TrackerMapTrailSeedInput(
                mode = state.mode,
                runtimeRunning = state.runtime.isRunning,
                activeTrackerId = effectiveDisplayedTrackerId(state),
                sessionVisibleBoundaryId = state.runtime.sessionVisibleBoundaryId,
                rosterTrackerIds = trackerManagementStateStore.trackers.value.map { it.id }.toSet(),
                groupSelection = resolveGroupModeSelection(state),
            )
        )
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

    private fun setGeometryLoading(isLoading: Boolean) {
        val current = _uiState.value
        if (current.isGeometryLoading == isLoading) return
        _uiState.value = current.copy(isGeometryLoading = isLoading)
    }

    override fun onCleared() {
        pointEventChannel.close()
        fitTrailSignal.close()
        super.onCleared()
    }
}
