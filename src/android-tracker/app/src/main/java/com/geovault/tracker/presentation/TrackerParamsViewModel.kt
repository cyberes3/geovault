package com.geovault.tracker.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.geovault.common.NaturalSort
import com.geovault.tracker.AppError
import com.geovault.tracker.R
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.SelectedTrackerPrefs
import com.geovault.tracker.Tracker
import com.geovault.tracker.data.TrackerDetailRepository
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.params.TrackerParamGridRow
import com.geovault.tracker.params.TrackerParamValueFormatter
import com.geovault.tracker.params.TrackerParamsBodyKind
import com.geovault.tracker.params.TrackerParamsContentReducer
import com.geovault.tracker.params.TrackerParamsRouteArgs
import com.geovault.tracker.policy.TrackPointBus
import com.geovault.tracker.policy.TrackerParamsPointAcceptancePolicy
import com.geovault.tracker.services.TrackingMotionMode
import com.geovault.tracker.services.TrackingRuntimeStateStore
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

data class TrackerParamsScreenUiState(
    val trackerTitle: String?,
    val lastUpdateText: String,
    val positionText: String,
    val motionModeText: String?,
    val bodyKind: TrackerParamsBodyKind,
    val gridRows: List<TrackerParamGridRow>,
    val showBlockingLoader: Boolean,
    val isRefreshing: Boolean,
    val errorMessage: String?,
)

class TrackerParamsViewModel(
    application: Application,
    private val args: TrackerParamsRouteArgs,
    private val detailRepository: TrackerDetailRepository,
) : AndroidViewModel(application) {

    private val formatter = TrackerParamValueFormatter(application)
    private val streamingController = TrackerParamsStreamingController(application.applicationContext)

    private val _uiState = MutableStateFlow(buildInitialUiState())
    val uiState: StateFlow<TrackerParamsScreenUiState> = _uiState.asStateFlow()

    private var pointStreamJob: Job? = null
    private var screenStarted = false
    private var streamTrackerName: String? = args.seed.displayName.trim().ifBlank { null }

    @Volatile
    private var lastAppliedTimestampMs: Long = 0L

    init {
        viewModelScope.launch {
            TrackingRuntimeStateStore.state.collect { runtime ->
                _uiState.update {
                    it.copy(motionModeText = motionModeLabel(runtime, args.trackerId))
                }
                if (screenStarted) {
                    startParamsStreaming(runtime)
                }
            }
        }
        loadTrackerData(refresh = false)
    }

    fun onScreenStarted() {
        screenStarted = true
        startParamsStreaming(TrackingRuntimeStateStore.state.value)
        if (pointStreamJob?.isActive == true) return
        pointStreamJob = viewModelScope.launch {
            TrackPointBus.events.collect { event ->
                val latestSelectedId = SelectedTrackerPrefs.selectedTrackerId(getApplication())
                val trackingRunning = TrackingRuntimeStateStore.state.value.isRunning
                if (
                    TrackerParamsPointAcceptancePolicy.shouldAcceptForParams(
                        event = event,
                        trackerId = args.trackerId,
                        trackingRunning = trackingRunning,
                        selectedTrackerId = latestSelectedId,
                    )
                ) {
                    applyPointPayload(
                        timestampMs = event.timestampMs,
                        lat = event.lat,
                        lon = event.lon,
                        paramsMap = parsePropsJson(event.propsJson),
                    )
                }
            }
        }
    }

    fun onScreenStopped() {
        screenStarted = false
        pointStreamJob?.cancel()
        pointStreamJob = null
        streamingController.onScreenStopped()
        _uiState.update { it.copy(isRefreshing = false) }
    }

    private fun startParamsStreaming(runtime: TrackingRuntimeSnapshot) {
        val app = getApplication<Application>()
        val selectedId = SelectedTrackerPrefs.selectedTrackerId(app)
        streamingController.onScreenStarted(
            trackerId = args.trackerId,
            trackerName = streamTrackerName,
            selectedTrackerId = selectedId,
            trackingRunning = runtime.isRunning,
        )
    }

    fun loadTrackerData(refresh: Boolean = false) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val selectedId = SelectedTrackerPrefs.selectedTrackerId(app)
            val runtime = TrackingRuntimeStateStore.state.value
            val localLive = isLocalTrackingMode(runtime, selectedId, args.trackerId)
            if (refresh) {
                _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
            } else if (!localLive) {
                _uiState.update { it.copy(showBlockingLoader = true, errorMessage = null) }
            }
            if (localLive) {
                applyLatestLocalTrackingPoint(runtime)
                _uiState.update {
                    it.copy(showBlockingLoader = false, isRefreshing = false)
                }
                return@launch
            }
            when (val result = detailRepository.loadTrackerMetadata(args.trackerId, forceRefresh = refresh)) {
                is RepositoryResult.Success -> {
                    applyFromTracker(result.data)
                    viewModelScope.launch {
                        detailRepository.refreshTrackers()
                    }
                    _uiState.update {
                        it.copy(showBlockingLoader = false, isRefreshing = false, errorMessage = null)
                    }
                }
                is RepositoryResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            showBlockingLoader = false,
                            isRefreshing = false,
                            errorMessage = appErrorMessage(result.error),
                        )
                    }
                }
            }
        }
    }

    private fun buildInitialUiState(): TrackerParamsScreenUiState {
        val seed = args.seed
        val app = getApplication<Application>()
        val title = seed.displayName.takeIf { it.isNotBlank() }?.uppercase(Locale.getDefault())
        val positionPair = if (seed.latitude != null && seed.longitude != null) {
            Pair(seed.latitude, seed.longitude)
        } else {
            null
        }
        val lastText = seed.lastUpdateMs?.takeIf { it > 0 }?.let(::formatTimeLocal)
            ?: app.getString(R.string.no_points_yet)
        val posText = positionPair?.let { formatLatLon(it.first, it.second) } ?: "-"
        val params = seed.initialParams.orEmpty()
        val bodyKind = TrackerParamsContentReducer.resolve(
            latestPointParams = params,
            lastTimestampMs = seed.lastUpdateMs?.takeIf { it > 0 },
            lastPosition = positionPair,
        )
        val gridRows = buildGridRows(bodyKind, params)
        return TrackerParamsScreenUiState(
            trackerTitle = title,
            lastUpdateText = lastText,
            positionText = posText,
            motionModeText = null,
            bodyKind = bodyKind,
            gridRows = gridRows,
            showBlockingLoader = true,
            isRefreshing = false,
            errorMessage = null,
        )
    }

    private fun applyLatestLocalTrackingPoint(runtime: TrackingRuntimeSnapshot) {
        val lat = runtime.lastTrackedLatitude ?: Double.NaN
        val lon = runtime.lastTrackedLongitude ?: Double.NaN
        val ts = runtime.lastTrackedTimestampMs
        applyPointPayload(ts, lat, lon, parsePropsJson(runtime.lastTrackedPropsJson))
    }

    private fun applyPointPayload(
        timestampMs: Long,
        lat: Double,
        lon: Double,
        paramsMap: Map<String, Any?>,
    ) {
        synchronized(this) {
            if (timestampMs > 0L && timestampMs < lastAppliedTimestampMs) {
                return
            }
            if (timestampMs > 0L) {
                lastAppliedTimestampMs = maxOf(lastAppliedTimestampMs, timestampMs)
            }
        }
        val app = getApplication<Application>()
        val lastText = if (timestampMs > 0L) {
            formatTimeLocal(timestampMs)
        } else {
            app.getString(R.string.no_points_yet)
        }
        val posText = if (!lat.isNaN() && !lon.isNaN()) {
            formatLatLon(lat, lon)
        } else {
            "-"
        }
        val lastMsOrNull = timestampMs.takeIf { it > 0L }
        val positionPair = if (!lat.isNaN() && !lon.isNaN()) Pair(lat, lon) else null
        val bodyKind = TrackerParamsContentReducer.resolve(paramsMap, lastMsOrNull, positionPair)
        val gridRows = buildGridRows(bodyKind, paramsMap)
        _uiState.update { prev ->
            prev.copy(
                lastUpdateText = lastText,
                positionText = posText,
                bodyKind = bodyKind,
                gridRows = gridRows,
            )
        }
    }

    private fun applyFromTracker(tracker: Tracker) {
        val lastTs = tracker.lastTimestampMs()
        val pos = tracker.lastPositionPair()
        val latestParams = tracker.point_params?.lastOrNull().orEmpty()
        val shouldApplyPoint = synchronized(this) {
            if (lastTs != null && lastTs > 0L) {
                if (lastTs < lastAppliedTimestampMs) {
                    false
                } else {
                    lastAppliedTimestampMs = maxOf(lastAppliedTimestampMs, lastTs)
                    true
                }
            } else {
                lastAppliedTimestampMs == 0L
            }
        }
        val app = getApplication<Application>()
        tracker.name.trim().takeIf { it.isNotBlank() }?.let { streamTrackerName = it }
        val title = tracker.name.takeIf { it.isNotBlank() }?.uppercase(Locale.getDefault())
        if (!shouldApplyPoint) {
            _uiState.update { prev ->
                prev.copy(trackerTitle = title ?: prev.trackerTitle)
            }
            return
        }
        val lastText = if (lastTs != null && lastTs > 0L) {
            formatTimeLocal(lastTs)
        } else {
            app.getString(R.string.no_points_yet)
        }
        val posText = pos?.let { formatLatLon(it.first, it.second) } ?: "-"
        val bodyKind = TrackerParamsContentReducer.resolve(latestParams, lastTs, pos)
        val gridRows = buildGridRows(bodyKind, latestParams)
        _uiState.update { prev ->
            prev.copy(
                trackerTitle = title ?: prev.trackerTitle,
                lastUpdateText = lastText,
                positionText = posText,
                bodyKind = bodyKind,
                gridRows = gridRows,
            )
        }
    }

    private fun buildGridRows(
        kind: TrackerParamsBodyKind,
        map: Map<String, Any?>,
    ): List<TrackerParamGridRow> {
        if (kind != TrackerParamsBodyKind.ShowingGrid) return emptyList()
        return map.entries
            .sortedWith(NaturalSort.naturalOrderBy { it.key })
            .map { (k, v) ->
                TrackerParamGridRow(
                    label = formatter.labelForKey(k),
                    value = formatter.formatDisplay(k, v),
                )
            }
    }

    private fun motionModeLabel(runtime: TrackingRuntimeSnapshot, trackerId: String): String? {
        val isLocalTracking = runtime.isRunning &&
            runtime.selectedTrackerId.isNotEmpty() &&
            trackerId == runtime.selectedTrackerId
        if (!isLocalTracking || !runtime.autoTrackingEnabled) return null
        val modeRes = when (runtime.activeMotionMode) {
            TrackingMotionMode.WALKING -> R.string.settings_tracker_profile_walking
            TrackingMotionMode.BIKING -> R.string.settings_tracker_profile_biking
            TrackingMotionMode.DRIVING -> R.string.settings_tracker_profile_driving
        }
        val app = getApplication<Application>()
        val modeName = app.getString(modeRes)
        return app.getString(R.string.track_mode_label, modeName)
    }

    private fun formatTimeLocal(ms: Long): String {
        val sdf = SimpleDateFormat("MMM d, yyyy, h:mm:ss a", Locale.getDefault())
        return sdf.format(Date(ms))
    }

    private fun formatLatLon(lat: Double, lon: Double): String {
        return "%.6f, %.6f".format(Locale.US, lat, lon)
    }

    private fun parsePropsJson(json: String?): Map<String, Any?> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            obj.keys().asSequence().associateWith { key -> obj.opt(key) }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun appErrorMessage(error: AppError): String {
        val ctx = getApplication<Application>()
        return when (error) {
            AppError.MissingServerUrl -> ctx.getString(R.string.trackers_error_missing_server)
            AppError.Network -> ctx.getString(R.string.trackers_error_network)
            AppError.Unauthorized -> ctx.getString(R.string.trackers_error_unauthorized)
            AppError.NotFound -> ctx.getString(R.string.trackers_error_not_found)
            is AppError.Server -> ctx.getString(R.string.trackers_error_server, error.code)
            is AppError.Validation -> error.message?.takeIf { it.isNotBlank() }
                ?: ctx.getString(R.string.trackers_error_validation)
            AppError.Unknown -> ctx.getString(R.string.trackers_error_unknown)
        }
    }

    override fun onCleared() {
        onScreenStopped()
        super.onCleared()
    }

    companion object {
        fun factory(application: Application, args: TrackerParamsRouteArgs): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val repo = TrackerAppServices.from(application).trackerDetailRepository()
                    return TrackerParamsViewModel(application, args, repo) as T
                }
            }
    }
}

private fun isLocalTrackingMode(
    runtime: TrackingRuntimeSnapshot,
    selectedTrackerId: String,
    trackerId: String,
): Boolean {
    return runtime.isRunning &&
        selectedTrackerId.isNotEmpty() &&
        trackerId == selectedTrackerId
}

private fun Tracker.lastTimestampMs(): Long? {
    val coord = last_point ?: return null
    if (coord.size < 3) return null
    val value = coord[2].toLong()
    return if (value < 1_000_000_000_000L) value * 1000L else value
}

private fun Tracker.lastPositionPair(): Pair<Double, Double>? {
    val coord = last_point ?: return null
    if (coord.size < 2) return null
    return Pair(coord[1], coord[0])
}
