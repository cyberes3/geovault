package com.geovault.tracker.presentation

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.tracker.Tracker
import com.geovault.tracker.RepositoryResult
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.tracker.db.AppDatabase
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.data.TrackerManagementRepository
import com.geovault.tracker.data.TrackerManagementStateStore
import com.geovault.tracker.policy.TrackPointBus
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.location.TrackingLifecycleState
import com.geovault.tracker.services.LiveStreamRuntimeStateStore
import com.geovault.tracker.services.RecordingRuntime
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import com.geovault.tracker.services.TrackingRuntimeStateStore
import com.geovault.tracker.ui.TrackerPointTimestamps
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val renderMetadataSignature: String = "",
)

data class TrackerMapRenderPackage(
    val renderState: com.geovault.common.maps.render.MapRenderState = com.geovault.common.maps.render.MapRenderState(),
    val bounds: LatLngBounds? = null,
    val selectionLockPoint: Pair<Double, Double>? = null,
    val revision: Long = 0L,
)

data class TrackerMapSelectionCard(
    val trackerId: String,
    val trackerName: String,
    val latitude: Double,
    val longitude: Double,
    val lastUpdatedMs: Long?,
    val accuracyMeters: Float?,
    val isOwned: Boolean,
    val serverMetadataUpdatedAtMs: Long? = null,
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
            rosterTrackerIds: Set<String>,
            groupTrackerIds: Set<String> = emptySet(),
            groupId: String? = null,
        ): Set<String> {
            return TrackerMapSessionProjector.project(
                TrackerMapSessionIntent(
                    mode = mode,
                    runtime = TrackingRuntimeSnapshot(
                        isRunning = runtimeRunning,
                        recordingRuntime = RecordingRuntime(
                            sessionActive = runtimeRunning,
                            selectedTrackerId = selectedTrackerId,
                        ),
                        selectedTrackerId = selectedTrackerId,
                    ),
                    displayedTrackerId = displayedTrackerId,
                    displayedTrackerName = "",
                    rosterTrackerIds = rosterTrackerIds,
                    groupSelection = TrackerMapGroupModeSelection(groupId = groupId, trackerIds = groupTrackerIds),
                    activeStreamedTrackerIds = emptySet(),
                )
            ).remoteSubscriptionIds
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
            return changed !in activeStreamedTrackerIds
        }

        @JvmStatic
        internal fun allQueueTrailsWithLocalRuntimeOverlay(
            mode: TrackerMapDisplayMode,
            runtime: TrackingRuntimeSnapshot,
            groupTrackerIds: Set<String>,
            allQueueTrailsByTracker: Map<String, List<QueuedLocation>>,
            nowMs: Long,
        ): Map<String, List<QueuedLocation>> {
            if (mode != TrackerMapDisplayMode.ALL_QUEUE && mode != TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
                return allQueueTrailsByTracker
            }
            if (!runtime.localRecordingActive) return allQueueTrailsByTracker
            val trackerId = runtime.selectedTrackerId.trim()
            if (trackerId.isEmpty()) return allQueueTrailsByTracker
            if (mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER && trackerId !in groupTrackerIds) {
                return allQueueTrailsByTracker
            }
            val lat = runtime.lastTrackedLatitude ?: return allQueueTrailsByTracker
            val lon = runtime.lastTrackedLongitude ?: return allQueueTrailsByTracker
            val point = QueuedLocation(
                id = 0L,
                trackerId = trackerId,
                time = runtime.lastTrackedTimestampMs.takeIf { it > 0L } ?: nowMs,
                latitude = lat,
                longitude = lon,
                altitude = null,
                speed = null,
                bearing = null,
                accuracy = runtime.lastAccuracyMeters,
                sat = null,
                prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS_RUNTIME,
                dist = null,
            )
            val currentTrail = allQueueTrailsByTracker[trackerId].orEmpty()
            val last = currentTrail.lastOrNull()
            val nextTrail = if (last != null) {
                val duplicate = last.time == point.time &&
                    last.latitude == point.latitude &&
                    last.longitude == point.longitude
                if (duplicate) {
                    currentTrail
                } else {
                    (currentTrail + point).takeLast(TRAIL_POINT_LIMIT)
                }
            } else {
                listOf(point)
            }
            if (nextTrail === currentTrail) return allQueueTrailsByTracker
            return allQueueTrailsByTracker.toMutableMap().apply {
                this[trackerId] = nextTrail
            }
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

        internal fun geometryContentFingerprint(coordinates: List<List<Double>>?): String {
            return coordinates.orEmpty().joinToString(separator = ";") { coordinate ->
                coordinate.joinToString(separator = ",") { value -> value.toString() }
            }
        }

        @JvmStatic
        internal fun filterRemoteLastPointsForAcceptedIds(
            remoteLastPoints: Map<String, TrackPointEvent>,
            acceptedRemoteTrackerIds: Set<String>,
        ): Map<String, TrackPointEvent> {
            val acceptedIds = acceptedRemoteTrackerIds
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
            if (acceptedIds.isEmpty()) return emptyMap()
            return remoteLastPoints.filterKeys { it.trim() in acceptedIds }
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
    private val _renderPackage = MutableStateFlow(TrackerMapRenderPackage())
    val renderPackage: StateFlow<TrackerMapRenderPackage> = _renderPackage.asStateFlow()

    fun trackerRosterForMapChip(): List<Tracker> = trackerManagementStateStore.trackers.value

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
    private val trailReloadMutex = Mutex()
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
            _uiState.collect {
                publishRenderPackage()
            }
        }
        viewModelScope.launch {
            TrackingRuntimeStateStore.state.collect { snap ->
                val effectiveLifecycleState = if (!snap.isRunning && snap.startupActive) {
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
            TrackPointBus.events.collect { point ->
                pointEventChannel.send(point)
            }
        }
        viewModelScope.launch {
            LiveStreamRuntimeStateStore.state.collectLatest { snapshot ->
                val wasRunning = lastObservedStreamingRunning
                lastObservedStreamingRunning = snapshot.isRunning
                _uiState.update { current ->
                    val plan = projectSession(
                        state = current.copy(activeStreamedTrackerIds = snapshot.activeTrackerIds),
                        groupSelection = resolveGroupModeSelection(current),
                        visibleRosterTrackerIds = visibleMapRosterTrackerIds(),
                    )
                    current.copy(
                        activeStreamedTrackerIds = snapshot.activeTrackerIds,
                        remoteLastPoints = if (
                            snapshot.lifecycleState == TrackingLifecycleState.STOPPED &&
                            current.streamTargetIds.isEmpty()
                        ) {
                            emptyMap()
                        } else {
                            filterRemoteLastPointsForAcceptedIds(
                                remoteLastPoints = current.remoteLastPoints,
                                acceptedRemoteTrackerIds = plan.acceptedRemoteTrackerIds,
                            )
                        },
                        streamingStatus = TrackerMapStreamingStatusPolicy.resolve(
                            snapshot = snapshot,
                            streamTargetIds = current.streamTargetIds,
                        ),
                    )
                }
                if (wasRunning && !snapshot.isRunning &&
                    snapshot.lifecycleState == TrackingLifecycleState.STOPPED
                ) {
                    restoreSelectedTrackerAfterStreamingStop()
                }
                reconcileStreaming(_uiState.value)
            }
        }
        viewModelScope.launch {
            combine(
                trackerManagementStateStore.trackers,
                trackerManagementStateStore.groups,
                trackerManagementStateStore.mapVisibility
            ) { trackers, groups, visibility ->
                val trackerFingerprint = trackers.joinToString(separator = "|") { tracker ->
                    "${tracker.id}:${tracker.updated_at ?: 0L}:${tracker.name}:${tracker.color}:" +
                        geometryContentFingerprint(tracker.geometry?.coordinates)
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
            }.distinctUntilChanged().collectLatest { metadataSignature ->
                _uiState.value = _uiState.value.copy(renderMetadataSignature = metadataSignature)
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
                                displayedTrackerId = TrackerMapDisplayIds.effectiveDisplayedTrackerId(state),
                                runtimeRunning = state.runtime.localRecordingActive,
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
                trackerManagementStateStore.trackers.value
                    .firstOrNull { it.id == normalizedId }
                    ?.name
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: state.displayedTrackerName.takeIf { state.displayedTrackerId == normalizedId }
                    ?: normalizedId
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
            TrackerMapDisplayIds.effectiveDisplayedTrackerId(state)
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
        val snapshot = buildCurrentSessionSnapshot()
        val state = snapshot.uiState
        val selection = buildSelectionCard(snapshot, normalizedTrackerId)
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
        val displayedId = TrackerMapDisplayIds.effectiveDisplayedTrackerId(state)
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
        return selectionLockPointOrNull(buildCurrentSessionSnapshot())
    }

    private fun selectionLockPointOrNull(
        snapshot: TrackerMapSessionSnapshot
    ): Pair<Double, Double>? {
        val state = snapshot.uiState
        val trackerId = state.selectionLockTrackerId.trim()
        if (trackerId.isEmpty()) return null
        snapshot.tracks[trackerId]?.renderTrail?.lastOrNull()?.let { point ->
            return point.latitude to point.longitude
        }
        snapshot.acceptedRemoteLastPoints[trackerId]?.let { point ->
            return point.lat to point.lon
        }
        val point = resolveTrackerPointData(snapshot, trackerId) ?: return null
        return point.latitude to point.longitude
    }

    private fun buildSelectionCard(
        snapshot: TrackerMapSessionSnapshot,
        trackerId: String
    ): TrackerMapSelectionCard? {
        val state = snapshot.uiState
        val tracker = trackerManagementStateStore.trackers.value.firstOrNull { it.id == trackerId }
        val point = resolveTrackerPointData(snapshot, trackerId) ?: return null
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
            serverMetadataUpdatedAtMs = tracker?.let(TrackerPointTimestamps::serverMetadataUpdatedAtMs),
        )
    }

    private fun resolveTrackerPointData(
        snapshot: TrackerMapSessionSnapshot,
        trackerId: String
    ): TrackerMapResolvedPoint? {
        val normalizedId = trackerId.trim()
        if (normalizedId.isEmpty()) return null
        val tracker = trackerManagementStateStore.trackers.value.firstOrNull { it.id == normalizedId }
        val effectiveState = snapshot.uiState.copy(
            trail = snapshot.singleTrail,
            allQueueTrailsByTracker = snapshot.renderTrailsByTracker,
            remoteLastPoints = snapshot.acceptedRemoteLastPoints,
        )
        return TrackerMapLastPointResolver.resolve(
            state = effectiveState,
            trackerId = trackerId,
            tracker = tracker,
            acceptedRemoteTrackerIds = snapshot.plan.acceptedRemoteTrackerIds,
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
            remoteLastPoints = emptyMap(),
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
                trackingRunning = state.runtime.localRecordingActive,
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
                displayedTrackerId = TrackerMapDisplayIds.effectiveDisplayedTrackerId(state),
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
                _uiState.update { cur ->
                    cur.copy(
                        streamTargetIds = ids,
                        remoteLastPoints = filterRemoteLastPointsForAcceptedIds(cur.remoteLastPoints, ids),
                    )
                }
                streamingReconciler.invalidateDedupe()
                reconcileStreaming(_uiState.value)
            }
            TrackerMapResumeDecision.ClearSingleTrackerState -> {
                pendingReopenSingleTrackerLoadId = null
                _uiState.update { cur ->
                    cur.copy(
                        displayedTrackerId = "",
                        displayedTrackerName = "",
                        remoteLastPoints = emptyMap(),
                        streamTargetIds = emptySet(),
                    ).withClearedMapSelectionCard()
                }
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

    private fun publishRenderPackage() {
        val nowMs = System.currentTimeMillis()
        val snapshot = buildCurrentSessionSnapshot(nowMs = nowMs)
        _renderPackage.update { current ->
            TrackerMapRenderPackage(
                renderState = buildMapRenderState(snapshot),
                bounds = trailBoundsOrNull(snapshot, nowMs),
                selectionLockPoint = selectionLockPointOrNull(snapshot),
                revision = current.revision + 1L,
            )
        }
    }

    private fun buildCurrentSessionSnapshot(nowMs: Long = System.currentTimeMillis()): TrackerMapSessionSnapshot {
        return buildSessionSnapshotForState(_uiState.value, nowMs)
    }

    private fun buildSessionSnapshotForState(
        state: TrackerMapUiState,
        nowMs: Long = System.currentTimeMillis(),
    ): TrackerMapSessionSnapshot {
        val groupSelection = resolveGroupModeSelection(state)
        val plan = projectSession(
            state = state,
            groupSelection = groupSelection,
            visibleRosterTrackerIds = visibleMapRosterTrackerIds(),
        )
        val renderTrails = allQueueTrailsWithLocalRuntimeOverlay(
            mode = state.mode,
            runtime = state.runtime,
            groupTrackerIds = plan.groupTrackerIds,
            allQueueTrailsByTracker = state.allQueueTrailsByTracker,
            nowMs = nowMs,
        )
        return TrackerMapSessionEngine.build(
            TrackerMapSessionBuildInput(
                state = state,
                plan = plan,
                localRuntimeOverlayTrails = renderTrails,
            )
        )
    }

    fun buildMapRenderState(): com.geovault.common.maps.render.MapRenderState {
        val snapshot = buildCurrentSessionSnapshot()
        return buildMapRenderState(snapshot)
    }

    private fun buildMapRenderState(
        snapshot: TrackerMapSessionSnapshot
    ): com.geovault.common.maps.render.MapRenderState {
        val s = snapshot.uiState
        val renderAllQueueTrailsByTracker = snapshot.renderTrailsByTracker
        val trackerColors = trackerManagementStateStore.trackers.value.associate { it.id to (it.color ?: "") }
        val trackerDisplayNames = trackerManagementStateStore.trackers.value.associate { it.id to it.name }
        val trackerRenderOrder = trackerManagementStateStore.trackers.value.map { it.id }
        val effectiveDisplayedId = TrackerMapDisplayIds.effectiveDisplayedTrackerId(s)
        val effectiveMapState = s.copy(
            trail = snapshot.singleTrail,
            allQueueTrailsByTracker = renderAllQueueTrailsByTracker,
            remoteLastPoints = snapshot.acceptedRemoteLastPoints,
        )
        val streamedAccuracyByTrackerId = buildStreamedAccuracyByTrackerId(
            effectiveMapState,
            effectiveDisplayedId,
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
                selectedMapTrackerId = resolveRenderSelectedMapTrackerId(
                    isBottomCardVisible = s.isBottomCardVisible,
                    selectedMapTrackerId = s.selectedMapTracker?.trackerId
                ),
                trackerRenderOrder = trackerRenderOrder,
                defaultIconColorHex = GeoVaultColorTokens.Hex.Blue400,
            ),
            accuracy = TrackerMapAccuracyRenderModel(
                streamedAccuracyByTrackerId = streamedAccuracyByTrackerId,
                fallbackAccuracyByTrackerId = fallbackAccuracyByTrackerId,
                allowAccuracyFallbackByTrackerId = allowAccuracyFallbackByTrackerId,
            ),
        )
    }

    fun trailBoundsOrNull(): LatLngBounds? {
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
            val renderAllQueueTrailsByTracker = snapshot.renderTrailsByTracker
            if (s.liveActiveFitEnabled) {
                val trackers = trackerManagementStateStore.trackers.value
                val activeBounds = TrackerMapLiveActiveFitPolicy.activeTrailBoundsResult(
                    allQueueTrailsByTracker = renderAllQueueTrailsByTracker,
                    remoteLastPoints = snapshot.acceptedRemoteLastPoints,
                    acceptedRemoteTrackerIds = sessionPlan.acceptedRemoteTrackerIds,
                    trackers = trackers,
                    nowMs = nowMs,
                )
                return when (activeBounds) {
                    is LiveActiveTrailBoundsResult.Active -> activeBounds.bounds
                    LiveActiveTrailBoundsResult.NoActiveTrackers -> null
                }
            }
            val multiBounds = TrackerMapStateTransforms.multiTrailBounds(renderAllQueueTrailsByTracker)
            val remoteBounds = TrackerMapStateTransforms.remoteLastPointBounds(snapshot.acceptedRemoteLastPoints)
            return TrackerMapStateTransforms.mergeBounds(multiBounds, remoteBounds)
                ?: TrackerMapStateTransforms.trailBounds(snapshot.singleTrail)
                ?: singlePointBoundsFromRuntime(s.runtime)
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

    private fun buildFallbackAccuracyByTrackerId(
        state: TrackerMapUiState,
        sessionPlan: TrackerMapStreamingPlan,
    ): Map<String, Float> {
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

    private fun refreshStreamTargets() {
        val state = _uiState.value
        val groupSelection = resolveGroupModeSelection(state)
        val visibleRosterTrackerIds = visibleMapRosterTrackerIds()
        val plan = projectSession(
            state = state,
            groupSelection = groupSelection,
            visibleRosterTrackerIds = visibleRosterTrackerIds,
        )
        val seed = TrackerMapReloadSeedPolicy.streamSeed(
            TrackerMapStreamSeedInput(
                mode = plan.mode,
                runtimeRunning = state.runtime.localRecordingActive,
                selectedTrackerId = plan.selectedTrackerId,
                displayedTrackerId = plan.displayedTrackerId,
                rosterTrackerIds = plan.visibleRosterTrackerIds,
                groupSelection = groupSelection
            )
        )
        if (seed == lastStreamTargetsSeed) {
            compactRemoteLastPoints(plan.acceptedRemoteTrackerIds)
            return
        }
        lastStreamTargetsSeed = seed
        _uiState.update { cur ->
            cur.copy(
                streamTargetIds = plan.remoteSubscriptionIds,
                remoteLastPoints = filterRemoteLastPointsForAcceptedIds(
                    remoteLastPoints = cur.remoteLastPoints,
                    acceptedRemoteTrackerIds = plan.acceptedRemoteTrackerIds,
                ),
                currentGroupId = if (state.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
                    plan.resolvedGroupId
                } else {
                    cur.currentGroupId
                },
                groupModeOptions = if (state.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
                    resolveGroupModeOptions()
                } else {
                    emptyList()
                },
            )
        }
    }

    private fun compactRemoteLastPoints(acceptedRemoteTrackerIds: Set<String>) {
        _uiState.update { cur ->
            val compacted = filterRemoteLastPointsForAcceptedIds(
                remoteLastPoints = cur.remoteLastPoints,
                acceptedRemoteTrackerIds = acceptedRemoteTrackerIds,
            )
            if (compacted === cur.remoteLastPoints || compacted == cur.remoteLastPoints) cur else cur.copy(remoteLastPoints = compacted)
        }
    }

    private fun projectSession(
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
            )
        )
    }

    private suspend fun reloadTrailFromDatabase(force: Boolean = false) {
        trailReloadMutex.withLock {
            reloadTrailFromDatabaseLocked(force)
        }
    }

    private suspend fun reloadTrailFromDatabaseLocked(force: Boolean) {
        val state = _uiState.value
        val groupSelection = resolveGroupModeSelection(state)
        val rosterTrackerIds = visibleMapRosterTrackerIds()
        val sessionPlan = projectSession(
            state = state,
            groupSelection = groupSelection,
            visibleRosterTrackerIds = rosterTrackerIds,
        )
        val activeTrackerId = sessionPlan.displayedTrackerId
        val guardInput = TrailReloadGuardInput(
            force = force,
            mode = state.mode,
            trailSize = state.trail.size,
            runtimeRunning = state.runtime.localRecordingActive,
            displayedTrackerId = activeTrackerId,
            trailReloadPlan = sessionPlan.trailReloadPlan,
        )
        if (!TrackerMapTrailReloadGuardPolicy.shouldProceed(guardInput)) return
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
        val plan = projectSession(
            state = workingState,
            groupSelection = groupSelection,
            visibleRosterTrackerIds = rosterTrackerIds,
        ).trailReloadPlan
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
                    multiTrails[overlayTrackerId] = loadQueueTrail(overlayTrackerId)
                }
                val fallbackTrail = multiTrails[plan.activeTrackerId].orEmpty()
                fallbackTrail to multiTrails.toMap()
            }
            TrackerMapTrailSource.SINGLE_QUEUE -> {
                loadQueueTrail(plan.activeTrackerId) to emptyMap()
            }
        }
        if (trailSeedForState(_uiState.value) != seed) {
            lastTrailLoadSeed = null
            return
        }
        val currentState = _uiState.value
        val mergedTrail = TrackerMapTrailMergePolicy.mergeServerTrailWithLiveOverlay(
            serverTrail = trail,
            currentTrail = currentState.trail,
            allowedLiveOverlayTrackerIds = setOfNotBlank(plan.activeTrackerId),
            trailPointLimit = TRAIL_POINT_LIMIT,
        )
        val mergedMultiTrails = TrackerMapTrailMergePolicy.mergeServerTrailsWithLiveOverlays(
            serverTrails = allQueueTrailsByTracker,
            currentTrails = currentState.allQueueTrailsByTracker,
            allowedLiveOverlayTrackerIds = plan.trackerIds + setOfNotBlank(plan.overlayTrackerId),
            trailPointLimit = TRAIL_POINT_LIMIT,
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

    private fun setOfNotBlank(value: String?): Set<String> {
        val normalized = value?.trim().orEmpty()
        return normalized.takeIf { it.isNotEmpty() }?.let(::setOf).orEmpty()
    }

    private suspend fun loadQueueTrail(trackerId: String): List<QueuedLocation> {
        val normalizedTrackerId = trackerId.trim()
        if (normalizedTrackerId.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            dao.getRecentChronologicalForTracker(normalizedTrackerId, TRAIL_POINT_LIMIT)
        }
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
            loadQueueTrail = { loadQueueTrail(trackerId) },
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
            loadQueueTrail = { id -> loadQueueTrail(id) },
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
        val hiddenOwnerTrackerIds = HiddenMapItemsPolicy.hiddenOwnerTrackerIds(trackerManagementStateStore.trackers.value)
        val preferredTrackerId = TrackerMapDisplayIds.effectiveDisplayedTrackerId(state).ifBlank { state.runtime.selectedTrackerId }
        return TrackerMapGroupModePolicy.resolveSelection(
            groups = trackerManagementStateStore.groups.value,
            hiddenGroupIds = hiddenGroupIds,
            hiddenTrackIds = hiddenTrackIds,
            hiddenOwnerTrackerIds = hiddenOwnerTrackerIds,
            preferredGroupId = state.currentGroupId,
            preferredTrackerId = preferredTrackerId
        )
    }

    private fun visibleMapRosterTrackerIds(): Set<String> {
        val trackers = trackerManagementStateStore.trackers.value
        return HiddenMapItemsPolicy.visibleTrackerIdsForMap(
            rosterTrackerIds = trackers.map { it.id },
            mapVisibility = trackerManagementStateStore.mapVisibility.value,
            trackers = trackers,
        )
    }

    private fun resolveGroupModeOptions(): List<TrackerMapGroupModeOption> {
        val visibility = trackerManagementStateStore.mapVisibility.value
        val hiddenGroupIds = visibility?.hidden_group_ids.orEmpty().toSet()
        val hiddenTrackIds = visibility?.hidden_track_ids.orEmpty().toSet()
        val hiddenOwnerTrackerIds = HiddenMapItemsPolicy.hiddenOwnerTrackerIds(trackerManagementStateStore.trackers.value)
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
                prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY,
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
        val reduction = TrackerMapSessionEngine.reducePoint(
            TrackerMapSessionPointInput(
                snapshot = buildCurrentSessionSnapshot(),
                point = point,
                trailPointLimit = TRAIL_POINT_LIMIT,
            )
        )
        if (reduction.shouldUpdate) {
            val nextState = stateWithRefreshedSelectionCard(
                state = reduction.nextSnapshot.uiState,
                changedTrackerId = point.trackId,
            )
            _uiState.value = nextState
            if (nextState.liveActiveFitEnabled) {
                requestFitTrail()
            }
        }
    }

    private fun stateWithRefreshedSelectionCard(
        state: TrackerMapUiState,
        changedTrackerId: String,
    ): TrackerMapUiState {
        val selection = state.selectedMapTracker ?: return state
        if (!state.isBottomCardVisible || selection.trackerId != changedTrackerId.trim()) return state
        val refreshed = buildSelectionCard(buildSessionSnapshotForState(state), selection.trackerId) ?: return state
        return state.copy(selectedMapTracker = refreshed)
    }

    fun acceptedRemoteTrackerIdsForCurrentSession(): Set<String> {
        return buildCurrentSessionSnapshot().plan.acceptedRemoteTrackerIds
    }

    private fun reconcileStreaming(state: TrackerMapUiState) {
        val plan = projectSession(state)
        streamingReconciler.reconcile(
            state,
            plan.displayedTrackerId,
            plan.displayedTrackerName,
            LiveStreamRuntimeStateStore.state.value,
        )
    }

    private fun effectiveDisplayedTrackerName(state: TrackerMapUiState): String {
        return state.displayedTrackerName.trim().ifBlank { state.runtime.selectedTrackerName.trim() }
    }

    private fun trailSeedForState(state: TrackerMapUiState): String {
        val groupSelection = resolveGroupModeSelection(state)
        val rosterIds = visibleMapRosterTrackerIds()
        val plan = projectSession(
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
