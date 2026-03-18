package com.geovault.tracker.fragments.map

import android.app.Application
import com.geovault.tracker.Group
import com.geovault.tracker.TrackingService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MapViewModel(application: Application) : AndroidViewModel(application) {
    private val trackRepository: MapTrackRepository = TrackerRepositoryMapTrackRepository()
    private val groupRepository: MapGroupRepository = TrackerRepositoryMapGroupRepository()
    private val visibilityRepository: MapVisibilityRepository = TrackerRepositoryMapVisibilityRepository()
    private val streamingRepository: MapStreamingRepository = TrackPointBusStreamingRepository()

    private val loadSingleTrackerUseCase = LoadSingleTrackerMapUseCase(trackRepository)
    private val loadAllTrackersUseCase = LoadAllTrackersMapUseCase(trackRepository, groupRepository, visibilityRepository)
    private val loadGroupMapUseCase = LoadGroupMapUseCase(trackRepository)
    private val handleTrackPointUseCase = HandleTrackPointUseCase()
    private val applyCameraPolicyUseCase = ApplyCameraPolicyUseCase()

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

    fun startTrackPointStream() {
        if (streamJob?.isActive == true) return
        streamJob = viewModelScope.launch {
            streamingRepository.events.collect { event ->
                val state = _uiState.value
                val accepted = handleTrackPointUseCase.shouldAccept(
                    event = event,
                    trackingRunning = TrackingService.isRunning,
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
                }
            }
        }
    }

    fun stopTrackPointStream() {
        streamJob?.cancel()
        streamJob = null
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

