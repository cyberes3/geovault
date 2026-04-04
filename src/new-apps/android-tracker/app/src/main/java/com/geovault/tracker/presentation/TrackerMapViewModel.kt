package com.geovault.tracker.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.db.AppDatabase
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.data.TrackerManagementRepository
import com.geovault.tracker.data.TrackerManagementStateStore
import com.geovault.tracker.policy.TrackPointBus
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.services.LiveStreamRuntimeStateStore
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import com.geovault.tracker.services.TrackingRuntimeStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val mode: TrackerMapDisplayMode = TrackerMapDisplayMode.SINGLE_SESSION,
    val followLockEnabled: Boolean = false,
)

class TrackerMapViewModel(application: Application) : AndroidViewModel(application) {

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

    init {
        viewModelScope.launch {
            TrackingRuntimeStateStore.state.collectLatest { snap ->
                _uiState.value = _uiState.value.copy(runtime = snap)
                val trail = withContext(Dispatchers.IO) {
                    dao.getRecentChronological(TRAIL_POINT_LIMIT)
                }
                _uiState.value = _uiState.value.copy(trail = trail)
                refreshStreamTargets()
            }
        }
        viewModelScope.launch {
            TrackPointBus.remoteStreamEvents.collectLatest { point ->
                if (_uiState.value.runtime.isRunning) return@collectLatest
                val activeIds = _uiState.value.activeStreamedTrackerIds
                if (activeIds.isEmpty() || point.trackId !in activeIds) return@collectLatest
                val current = _uiState.value.remoteLastPoints.toMutableMap()
                current[point.trackId] = point
                _uiState.value = _uiState.value.copy(remoteLastPoints = current)
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
        refreshStreamTargets()
    }

    fun setMode(mode: TrackerMapDisplayMode) {
        _uiState.value = _uiState.value.copy(mode = mode)
        refreshStreamTargets()
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
        return TrackerMapStateTransforms.trailBounds(s.trail)
            ?: singlePointBoundsFromRuntime(s.runtime)
    }

    private fun singlePointBoundsFromRuntime(runtime: TrackingRuntimeSnapshot): LatLngBounds? {
        val lat = runtime.lastTrackedLatitude ?: return null
        val lon = runtime.lastTrackedLongitude ?: return null
        return LatLngBounds.from(lat, lon, lat, lon)
    }

    companion object {
        private const val TRAIL_POINT_LIMIT = 4000
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
                "${state.mode}|${state.runtime.isRunning}|${state.runtime.selectedTrackerId}|$trackerRosterSignature"
            if (seed == lastStreamTargetsSeed) return@launch
            lastStreamTargetsSeed = seed
            val streamIds = when (state.mode) {
                TrackerMapDisplayMode.SINGLE_SESSION -> emptySet()
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
                        is RepositoryResult.Failure -> state.streamTargetIds
                    }
                }
            }
            _uiState.value = _uiState.value.copy(streamTargetIds = streamIds)
        }
    }
}
