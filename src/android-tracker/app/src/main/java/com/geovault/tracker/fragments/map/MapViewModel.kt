package com.geovault.tracker.fragments.map

import android.app.Application
import android.util.Log
import com.geovault.tracker.Group
import com.geovault.tracker.TrackerRepository
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
import kotlinx.coroutines.launch

@HiltViewModel
class MapViewModel @Inject constructor(
    application: Application,
    private val trackRepository: MapTrackRepository,
    private val groupRepository: MapGroupRepository,
    private val visibilityRepository: MapVisibilityRepository,
    private val streamingRepository: MapStreamingRepository
) : AndroidViewModel(application) {
    private companion object {
        const val TAG = "MapViewModel"
    }

    private val loadSingleTrackerUseCase = LoadSingleTrackerMapUseCase(trackRepository)
    private val loadAllTrackersUseCase = LoadAllTrackersMapUseCase(trackRepository, groupRepository, visibilityRepository)
    private val loadGroupMapUseCase = LoadGroupMapUseCase(trackRepository)
    private val handleTrackPointUseCase = HandleTrackPointUseCase()
    private val applyCameraPolicyUseCase = ApplyCameraPolicyUseCase()
    private val resolveMapResumeUseCase = ResolveMapResumeUseCase()

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private val _commands = MutableSharedFlow<MapCommand>(extraBufferCapacity = 128)
    val commands: SharedFlow<MapCommand> = _commands.asSharedFlow()

    private var streamJob: Job? = null

    fun handleIntent(intent: MapIntent) {
        when (intent) {
            is MapIntent.LoadSingleTracker -> loadSingle(intent.trackerId, intent.forceReplace)
            is MapIntent.LoadAllTrackers -> loadAllTrackers()
            is MapIntent.LoadGroup -> loadGroup(intent.group, intent.zoomToTrackerId)
        }
    }

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
                val accepted = handleTrackPointUseCase.shouldAccept(
                    event = event,
                    trackingRunning = TrackingRuntimeStateStore.state.value.isRunning,
                    showAllTrackers = state.showAllTrackers,
                    mapViewContext = when (state.mode) {
                        is MapScreenMode.GroupMode -> MapViewContext.GROUP
                        else -> MapViewContext.SINGLE_TRACKER
                    },
                    displayedTrackerId = state.displayedTrackerId,
                    activeStreamedTrackerIds = state.activeStreamedTrackerIds
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
        TrackerRepository.cancelGeometryRequest()
    }

    fun stopLiveTrackStreaming() {
        _uiState.value = _uiState.value.copy(activeStreamedTrackerIds = emptySet())
        MapStreamingServiceHelper.stopStreaming(getApplication())
    }

    fun startLiveTrackStreamingForTrackerSet(trackerIds: Set<String>, trackerName: String? = null) {
        if (TrackingRuntimeStateStore.state.value.isRunning) {
            stopLiveTrackStreaming()
            return
        }
        val cleanedIds = MapStreamingServiceHelper.startStreaming(getApplication(), trackerIds, trackerName)
        if (cleanedIds == null) {
            stopLiveTrackStreaming()
            return
        }
        _uiState.value = _uiState.value.copy(activeStreamedTrackerIds = cleanedIds)
    }

    internal fun startLiveTrackStreamingForDisplayedTracker(
        displayedTrackerId: String?,
        displayedTrackerName: String?,
        selectedTrackerId: String?,
        mapViewContext: MapViewContext
    ) {
        if (TrackingRuntimeStateStore.state.value.isRunning) {
            stopLiveTrackStreaming()
            return
        }
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
        trackingRunning: Boolean,
        showAllTrackers: Boolean,
        mapViewContext: MapViewContext,
        displayedTrackerId: String?
    ): Boolean {
        if (trackingRunning) return false
        if (!LiveStreamRuntimeStateStore.state.value.isRunning) return false
        val activeIds = _uiState.value.activeStreamedTrackerIds
        return if (showAllTrackers || mapViewContext == MapViewContext.GROUP) {
            activeIds.isNotEmpty()
        } else {
            val id = displayedTrackerId ?: return false
            id in activeIds
        }
    }

    private fun loadSingle(trackerId: String?, forceReplace: Boolean) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, mode = MapScreenMode.Single)
            val snapshot = loadSingleTrackerUseCase.execute(
                context = getApplication(),
                trackerId = trackerId,
                displayedTrackerId = _uiState.value.displayedTrackerId,
                forceReplace = forceReplace
            )
            if (snapshot == null) {
                _uiState.value = _uiState.value.copy(loading = false)
                _commands.tryEmit(MapCommand.ShowError("No tracker selected"))
                return@launch
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
            _commands.tryEmit(
                MapCommand.ApplyCameraPolicy(
                    applyCameraPolicyUseCase.forMode(MapScreenMode.Single, null, enableFollowLock = true)
                )
            )
        }
    }

    private fun loadAllTrackers() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                loading = true,
                showAllTrackers = true,
                displayedGroupName = null,
                mode = MapScreenMode.AllTrackers
            )
            val snapshot = loadAllTrackersUseCase.execute(getApplication())
            _uiState.value = _uiState.value.copy(loading = false)
            _commands.tryEmit(MapCommand.RenderAllTrackers(snapshot))
            _commands.tryEmit(
                MapCommand.ApplyCameraPolicy(
                    applyCameraPolicyUseCase.forMode(MapScreenMode.AllTrackers, null, enableFollowLock = false)
                )
            )
        }
    }

    private fun loadGroup(group: Group, zoomToTrackerId: String?) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                loading = true,
                showAllTrackers = true,
                displayedGroupName = group.name,
                mode = MapScreenMode.GroupMode(group)
            )
            val snapshot = loadGroupMapUseCase.execute(getApplication(), group, zoomToTrackerId)
            _uiState.value = _uiState.value.copy(loading = false)
            _commands.tryEmit(MapCommand.RenderAllTrackers(snapshot))
            _commands.tryEmit(
                MapCommand.ApplyCameraPolicy(
                    applyCameraPolicyUseCase.forMode(MapScreenMode.GroupMode(group), null, enableFollowLock = false)
                )
            )
        }
    }
}

