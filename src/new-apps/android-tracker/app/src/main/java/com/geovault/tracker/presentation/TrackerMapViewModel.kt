package com.geovault.tracker.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.tracker.db.AppDatabase
import com.geovault.tracker.db.QueuedLocation
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
    val mode: TrackerMapDisplayMode = TrackerMapDisplayMode.SINGLE_SESSION,
    val followLockEnabled: Boolean = false,
)

class TrackerMapViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.getDatabase(application).locationDao()

    private val _uiState = MutableStateFlow(TrackerMapUiState())
    val uiState: StateFlow<TrackerMapUiState> = _uiState.asStateFlow()

    private val fitTrailSignal = Channel<Unit>(Channel.CONFLATED)
    val fitTrailEvents = fitTrailSignal.receiveAsFlow()

    init {
        viewModelScope.launch {
            TrackingRuntimeStateStore.state.collectLatest { snap ->
                _uiState.value = _uiState.value.copy(runtime = snap)
                val trail = withContext(Dispatchers.IO) {
                    dao.getRecentChronological(TRAIL_POINT_LIMIT)
                }
                _uiState.value = _uiState.value.copy(trail = trail)
            }
        }
    }

    fun setMode(mode: TrackerMapDisplayMode) {
        _uiState.value = _uiState.value.copy(mode = mode)
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
}
