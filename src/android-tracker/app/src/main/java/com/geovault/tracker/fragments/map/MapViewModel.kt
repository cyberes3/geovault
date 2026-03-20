package com.geovault.tracker.fragments.map

import android.app.Application
import android.util.Log
import com.geovault.tracker.Group
import com.geovault.tracker.SelectedTrackerPrefs
import com.geovault.tracker.data.TrackerManagementEvent
import com.geovault.tracker.data.TrackerManagementStateStore
import com.geovault.tracker.services.LiveStreamRuntimeStateStore
import com.geovault.tracker.services.TrackingRuntimeStateStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@HiltViewModel
class MapViewModel @Inject constructor(
    application: Application,
    private val runtimeTrackRepository: RuntimeMapTrackRepository,
    private val bootstrapTrackRepository: BootstrapMapTrackRepository,
    private val groupRepository: MapGroupRepository,
    private val visibilityRepository: MapVisibilityRepository,
    private val streamingRepository: MapStreamingRepository,
    private val trackerManagementStateStore: TrackerManagementStateStore
) : AndroidViewModel(application) {
    private companion object {
        const val TAG = "MapViewModel"
        const val LOAD_TRACKERS_ERROR = "Failed To Load Trackers"
        const val LOAD_GROUP_ERROR = "Failed To Load Group Tracks"
    }

    private val loadSingleTrackerUseCase = LoadSingleTrackerMapUseCase(runtimeTrackRepository, bootstrapTrackRepository)
    private val loadAllTrackersUseCase = LoadAllTrackersMapUseCase(runtimeTrackRepository, groupRepository, visibilityRepository)
    private val loadGroupMapUseCase = LoadGroupMapUseCase(runtimeTrackRepository)
    private val handleTrackPointUseCase = HandleTrackPointUseCase()
    private val applyCameraPolicyUseCase = ApplyCameraPolicyUseCase()
    private val resolveMapResumeUseCase = ResolveMapResumeUseCase()

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private val _commands = MutableSharedFlow<MapCommand>(extraBufferCapacity = 128)
    val commands: SharedFlow<MapCommand> = _commands.asSharedFlow()

    private var streamJob: Job? = null
    private var streamRuntimeJob: Job? = null
    private var managementEventsJob: Job? = null
    private var mapLoadJob: Job? = null
    private var latestMapLoadRequestId: Long = 0L

    init {
        streamRuntimeJob = viewModelScope.launch {
            LiveStreamRuntimeStateStore.state.collectLatest { snapshot ->
                _uiState.value = _uiState.value.copy(
                    activeStreamedTrackerIds = snapshot.activeTrackerIds
                )
            }
        }
        managementEventsJob = viewModelScope.launch {
            trackerManagementStateStore.events.collectLatest { event ->
                when (event) {
                    is TrackerManagementEvent.HistoryCleared -> {
                        val current = _uiState.value
                        _uiState.value = current.copy(
                            historyClearSignalVersion = current.historyClearSignalVersion + 1L,
                            historyClearedTrackerId = event.trackerId
                        )
                        reloadVisibleMapData()
                    }
                    is TrackerManagementEvent.TrackerUpserted,
                    is TrackerManagementEvent.TrackerDeleted,
                    is TrackerManagementEvent.TrackersRefreshed,
                    is TrackerManagementEvent.GroupUpserted,
                    is TrackerManagementEvent.GroupDeleted,
                    is TrackerManagementEvent.GroupsRefreshed,
                    is TrackerManagementEvent.MapVisibilityChanged -> {
                        val current = _uiState.value
                        if (shouldReloadVisibleMapDataForEvent(current, event) &&
                            shouldExecuteReloadForCurrentState(current, event)
                        ) {
                            reloadVisibleMapData()
                        }
                    }
                }
            }
        }
    }

    private fun reloadVisibleMapData() {
        when (val mode = _uiState.value.mode) {
            is MapScreenMode.AllTrackers -> loadAllTrackers()
            is MapScreenMode.GroupMode -> loadGroup(mode.group, zoomToTrackerId = null)
            is MapScreenMode.Single -> loadSingle(
                trackerId = _uiState.value.displayedTrackerId,
                forceReplace = true,
                mode = SingleTrackerLoadMode.RUNTIME
            )
        }
    }

    private fun shouldReloadVisibleMapDataForEvent(
        state: MapUiState,
        event: TrackerManagementEvent
    ): Boolean {
        return when (event) {
            is TrackerManagementEvent.TrackerDeleted -> {
                when (state.mode) {
                    is MapScreenMode.Single -> state.displayedTrackerId == event.trackerId
                    else -> true
                }
            }
            is TrackerManagementEvent.TrackerUpserted,
            is TrackerManagementEvent.TrackersRefreshed,
            is TrackerManagementEvent.GroupUpserted,
            is TrackerManagementEvent.GroupDeleted,
            is TrackerManagementEvent.GroupsRefreshed,
            is TrackerManagementEvent.MapVisibilityChanged -> state.mode !is MapScreenMode.Single
            is TrackerManagementEvent.HistoryCleared -> true
        }
    }

    private fun shouldExecuteReloadForCurrentState(
        state: MapUiState,
        event: TrackerManagementEvent
    ): Boolean {
        if (state.mode !is MapScreenMode.Single) return true
        if (!state.loading) return true
        return when (event) {
            is TrackerManagementEvent.HistoryCleared -> true
            is TrackerManagementEvent.TrackerDeleted -> true
            else -> false
        }
    }

    fun consumeHistoryClearSignal(version: Long) {
        val current = _uiState.value
        if (current.historyClearSignalVersion == version && current.historyClearedTrackerId != null) {
            _uiState.value = current.copy(historyClearedTrackerId = null)
        }
    }

    private fun emitCameraPolicy(command: MapCameraCommand) {
        _uiState.value = _uiState.value.copy(lockMode = command.lockMode)
        _commands.tryEmit(MapCommand.ApplyCameraPolicy(command))
    }

    fun handleIntent(intent: MapIntent) {
        when (intent) {
            is MapIntent.LoadSingleTrackerRuntime -> loadSingle(
                trackerId = intent.trackerId,
                forceReplace = intent.forceReplace,
                mode = SingleTrackerLoadMode.RUNTIME
            )
            is MapIntent.LoadSingleTrackerBootstrap -> loadSingle(
                trackerId = intent.trackerId,
                forceReplace = intent.forceReplace,
                mode = SingleTrackerLoadMode.BOOTSTRAP
            )
            is MapIntent.LoadAllTrackers -> loadAllTrackers()
            is MapIntent.LoadGroup -> loadGroup(intent.group, intent.zoomToTrackerId)
        }
    }

    private fun launchLatestMapLoad(block: suspend (requestId: Long) -> Unit) {
        val requestId = ++latestMapLoadRequestId
        mapLoadJob?.cancel()
        mapLoadJob = viewModelScope.launch {
            block(requestId)
        }
    }

    private fun isLatestMapLoad(requestId: Long): Boolean = requestId == latestMapLoadRequestId

    fun updateUiState(transform: (MapUiState) -> MapUiState) {
        _uiState.value = transform(_uiState.value)
    }

    internal fun resolveResumeDecision(input: MapResumeInput): MapResumeDecision {
        return resolveMapResumeUseCase.resolve(input)
    }

    fun startTrackPointStream() {
        if (streamJob?.isActive == true) return
        streamJob = viewModelScope.launch {
            streamingRepository.events.collect { event ->
                val state = _uiState.value
                val effectiveDisplayedTrackerId = state.displayedTrackerId
                    ?: SelectedTrackerPrefs.selectedTrackerId(getApplication())
                val accepted = handleTrackPointUseCase.shouldAccept(
                    event = event,
                    trackingRunning = TrackingRuntimeStateStore.state.value.isRunning,
                    showAllTrackers = state.showAllTrackers,
                    mapViewContext = when (state.mode) {
                        is MapScreenMode.GroupMode -> MapViewContext.GROUP
                        else -> MapViewContext.SINGLE_TRACKER
                    },
                    displayedTrackerId = effectiveDisplayedTrackerId,
                    activeStreamedTrackerIds = LiveStreamRuntimeStateStore.state.value.activeTrackerIds
                )
                if (accepted) {
                    _commands.tryEmit(MapCommand.ApplyTrackPoint(event))
                } else if (event.source == com.geovault.tracker.pipeline.TrackPointSource.LOCAL_GPS) {
                    Log.d(
                        TAG,
                        "Dropped local GPS point trackId=${event.trackId} displayed=${state.displayedTrackerId} " +
                            "mode=${state.mode} trackingRunning=${TrackingRuntimeStateStore.state.value.isRunning}"
                    )
                }
            }
        }
    }

    fun stopTrackPointStream() {
        streamJob?.cancel()
        streamJob = null
    }

    fun cancelGeometryRequest() {
        runtimeTrackRepository.cancelGeometryRequest()
    }

    fun stopLiveTrackStreaming() {
        Log.d(TAG, "stopLiveTrackStreaming called", Exception("stopStreaming stacktrace"))
        MapStreamingServiceHelper.stopStreaming(getApplication())
    }

    fun startLiveTrackStreamingForTrackerSet(trackerIds: Set<String>, trackerName: String? = null) {
        val trackingRunning = TrackingRuntimeStateStore.state.value.isRunning
        val selectedTrackerId = SelectedTrackerPrefs.selectedTrackerId(getApplication())
        val eligibleIds = filterStreamEligibleTrackerIds(
            trackerIds = trackerIds,
            selectedTrackerId = selectedTrackerId,
            trackingRunning = trackingRunning
        )
        Log.d(TAG, "startLiveTrackStreamingForTrackerSet input=$trackerIds eligible=$eligibleIds tracking=$trackingRunning selected=$selectedTrackerId")
        val cleanedIds = MapStreamingServiceHelper.startStreaming(getApplication(), eligibleIds, trackerName)
        if (cleanedIds == null) {
            Log.d(TAG, "startLiveTrackStreamingForTrackerSet: no eligible IDs, stopping streaming")
            stopLiveTrackStreaming()
            return
        }
    }

    internal fun startLiveTrackStreamingForDisplayedTracker(
        displayedTrackerId: String?,
        displayedTrackerName: String?,
        selectedTrackerId: String?,
        mapViewContext: MapViewContext
    ) {
        Log.d(TAG, "startLiveTrackStreamingForDisplayedTracker displayed=$displayedTrackerId selected=$selectedTrackerId context=$mapViewContext")
        MapStreamingServiceHelper.updateStreamingForDisplayedTracker(
            displayedTrackerId = displayedTrackerId,
            displayedTrackerName = displayedTrackerName,
            selectedTrackerId = selectedTrackerId,
            mapViewContext = mapViewContext,
            startStreaming = { ids, name -> startLiveTrackStreamingForTrackerSet(ids, name) },
            stopStreaming = ::stopLiveTrackStreaming
        )
    }

    internal fun isStreaming(
        showAllTrackers: Boolean,
        mapViewContext: MapViewContext,
        displayedTrackerId: String?
    ): Boolean {
        if (!LiveStreamRuntimeStateStore.state.value.isRunning) return false
        val activeIds = LiveStreamRuntimeStateStore.state.value.activeTrackerIds
        return if (showAllTrackers || mapViewContext == MapViewContext.GROUP) {
            activeIds.isNotEmpty()
        } else {
            val id = displayedTrackerId ?: return false
            id in activeIds
        }
    }

    private fun filterStreamEligibleTrackerIds(
        trackerIds: Set<String>,
        selectedTrackerId: String,
        trackingRunning: Boolean
    ): Set<String> {
        if (!trackingRunning || selectedTrackerId.isBlank()) return trackerIds
        return trackerIds.filterTo(mutableSetOf()) { it != selectedTrackerId }
    }

    private fun loadSingle(
        trackerId: String?,
        forceReplace: Boolean,
        mode: SingleTrackerLoadMode
    ) {
        launchLatestMapLoad { requestId ->
            _uiState.value = _uiState.value.copy(loading = true, mode = MapScreenMode.Single)
            val snapshot = loadSingleTrackerUseCase.execute(
                context = getApplication(),
                trackerId = trackerId,
                displayedTrackerId = _uiState.value.displayedTrackerId,
                forceReplace = forceReplace,
                mode = mode
            )
            if (!isLatestMapLoad(requestId)) return@launchLatestMapLoad
            if (snapshot == null) {
                _uiState.value = _uiState.value.copy(loading = false)
                _commands.tryEmit(MapCommand.ShowError("No tracker selected"))
                return@launchLatestMapLoad
            }

            _uiState.value = _uiState.value.copy(
                loading = false,
                displayedTrackerId = snapshot.tracker.id,
                displayedTrackerName = snapshot.tracker.name,
                displayedGroupName = null,
                showAllTrackers = false,
                mode = MapScreenMode.Single
            )
            _commands.tryEmit(MapCommand.RenderSingleTracker(snapshot))
            emitCameraPolicy(applyCameraPolicyUseCase.forMode(MapScreenMode.Single, null, enableFollowLock = true))
        }
    }

    private fun loadAllTrackers() {
        launchLatestMapLoad { requestId ->
            _uiState.value = _uiState.value.copy(
                loading = true,
                showAllTrackers = true,
                displayedGroupName = null,
                mode = MapScreenMode.AllTrackers
            )
            val result = loadAllTrackersUseCase.execute()
            if (!isLatestMapLoad(requestId)) return@launchLatestMapLoad
            _uiState.value = _uiState.value.copy(loading = false)
            _commands.tryEmit(MapCommand.RenderAllTrackers(result.snapshot))
            if (result.hadFailures) {
                _commands.tryEmit(MapCommand.ShowError(LOAD_TRACKERS_ERROR))
            }
            emitCameraPolicy(applyCameraPolicyUseCase.forMode(MapScreenMode.AllTrackers, null, enableFollowLock = false))
        }
    }

    private fun loadGroup(group: Group, zoomToTrackerId: String?) {
        launchLatestMapLoad { requestId ->
            _uiState.value = _uiState.value.copy(
                loading = true,
                showAllTrackers = true,
                displayedGroupName = group.name,
                mode = MapScreenMode.GroupMode(group)
            )
            val result = loadGroupMapUseCase.execute(group, zoomToTrackerId)
            if (!isLatestMapLoad(requestId)) return@launchLatestMapLoad
            _uiState.value = _uiState.value.copy(loading = false)
            _commands.tryEmit(MapCommand.RenderAllTrackers(result.snapshot))
            if (result.hadFailures) {
                _commands.tryEmit(MapCommand.ShowError(LOAD_GROUP_ERROR))
            }
            emitCameraPolicy(applyCameraPolicyUseCase.forMode(MapScreenMode.GroupMode(group), null, enableFollowLock = false))
        }
    }
}

