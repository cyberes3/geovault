package com.geovault.tracker.presentation

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import com.geovault.common.logging.GeoVaultCaptureLog
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.common.UnitUtils
import com.geovault.tracker.R
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.data.GroupManagementRepository
import com.geovault.tracker.data.TrackerManagementRepository
import com.geovault.tracker.settings.TrackerSettings
import com.geovault.tracker.settings.TrackerSettingsLoadState
import com.geovault.tracker.settings.TrackerSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsState(
    val infoMessage: String? = null,
    val trackerLoadState: TrackerSettingsLoadState = TrackerSettingsLoadState.Loading,
    val trackerSettings: TrackerSettings = TrackerSettings(),
    val trackerRevision: Long = 0L,
    val hiddenTrackerItems: List<HiddenTrackerItem> = emptyList(),
    val isHiddenTrackerItemsLoading: Boolean = false,
    val usesImperialUnits: Boolean = false,
    val significantMotionSensorAvailable: Boolean = true,
)

class SettingsViewModel(
    application: Application,
    private val trackerSettingsRepository: TrackerSettingsRepository =
        TrackerAppServices.from(application).trackerSettingsRepository(),
    private val trackerManagementRepository: TrackerManagementRepository =
        TrackerAppServices.from(application).trackerManagementRepository(),
    private val groupManagementRepository: GroupManagementRepository =
        TrackerAppServices.from(application).groupManagementRepository(),
) : AndroidViewModel(application) {
    private companion object {
        const val TAG = "SettingsViewModel"
        val SHOW_ALL_CLEAR_TARGETS = listOf("trackers", "groups")
    }

    constructor(application: Application) : this(
        application,
        TrackerAppServices.from(application).trackerSettingsRepository(),
        TrackerAppServices.from(application).trackerManagementRepository(),
        TrackerAppServices.from(application).groupManagementRepository(),
    )

    private val appContext = application.applicationContext

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            trackerSettingsRepository.observeState().collect { tracker ->
                _state.update { current -> current.withTrackerState(tracker) }
            }
        }
    }

    fun initialize() {
        enforceMotionSensorSupport()
        refreshMeasurementDefaults()
        refreshHiddenTrackerItems()
    }

    fun onHostResumed() {
        enforceMotionSensorSupport()
        refreshMeasurementDefaults()
        refreshHiddenTrackerItems()
    }

    fun setLowAccuracyFallbackTimeoutSecFromInput(raw: String) {
        val value = raw.toLongOrNull() ?: return
        trackerSettingsRepository.setLowAccuracyFallbackTimeoutSec(value)
    }

    fun setLowAccuracyFallbackEnabled(enabled: Boolean) {
        trackerSettingsRepository.setLowAccuracyFallbackEnabled(enabled)
    }

    fun setStartOnBoot(enabled: Boolean) {
        trackerSettingsRepository.setStartOnBoot(enabled)
    }

    fun setStartTrackingOnLaunch(enabled: Boolean) {
        trackerSettingsRepository.setStartTrackingOnLaunch(enabled)
    }

    fun setSendExtendedData(enabled: Boolean) {
        trackerSettingsRepository.setSendExtendedData(enabled)
    }

    fun setSignificantDataOnly(enabled: Boolean) {
        if (enabled && !_state.value.significantMotionSensorAvailable) {
            _state.update {
                it.copy(infoMessage = appContext.getString(R.string.motion_sensor_unavailable_toast))
            }
            trackerSettingsRepository.setSignificantDataOnly(false)
            return
        }
        trackerSettingsRepository.setSignificantDataOnly(enabled)
    }

    fun setSparseTracking(enabled: Boolean) {
        trackerSettingsRepository.setSparseTracking(enabled)
    }

    fun setKeepScreenOnWhileViewingMap(enabled: Boolean) {
        trackerSettingsRepository.setKeepScreenOnWhileViewingMap(enabled)
    }

    fun setGroupModeFitOnlyActiveTrackers(enabled: Boolean) {
        trackerSettingsRepository.setGroupModeFitOnlyActiveTrackers(enabled)
    }

    fun clearMessage() {
        _state.update { it.copy(infoMessage = null) }
    }

    fun refreshHiddenTrackerItems() {
        viewModelScope.launch {
            _state.update { it.copy(isHiddenTrackerItemsLoading = true) }
            val trackers = when (val result = trackerManagementRepository.loadTrackers(forceRefresh = true)) {
                is RepositoryResult.Success -> result.data
                is RepositoryResult.Failure -> {
                    GeoVaultCaptureLog.w(TAG, "refreshHiddenTrackerItems: failed to load trackers")
                    _state.update { it.copy(isHiddenTrackerItemsLoading = false) }
                    return@launch
                }
            }
            val groups = when (val result = groupManagementRepository.loadGroups(forceRefresh = true)) {
                is RepositoryResult.Success -> result.data
                is RepositoryResult.Failure -> {
                    GeoVaultCaptureLog.w(TAG, "refreshHiddenTrackerItems: failed to load groups")
                    _state.update { it.copy(isHiddenTrackerItemsLoading = false) }
                    return@launch
                }
            }
            _state.update {
                it.copy(
                    isHiddenTrackerItemsLoading = false,
                    hiddenTrackerItems = HiddenTrackersPolicy.buildItems(trackers, groups)
                )
            }
        }
    }

    fun unhideTrackerItem(item: HiddenTrackerItem) {
        viewModelScope.launch {
            when (item.type) {
                HiddenTrackerItemType.TRACKER -> {
                    val tracker = when (val loadResult = trackerManagementRepository.loadTracker(item.id)) {
                        is RepositoryResult.Success -> loadResult.data
                        is RepositoryResult.Failure -> {
                            GeoVaultCaptureLog.w(TAG, "unhideTrackerItem: failed to load tracker ${item.id}")
                            return@launch
                        }
                    }
                    val result = trackerManagementRepository.updateTrackerSettings(
                        trackerId = item.id,
                        request = TrackerSharingSettingsPolicy.buildPreservingSettingsRequest(
                            tracker = tracker,
                            hidden = false,
                        )
                    )
                    if (result is RepositoryResult.Failure) {
                        GeoVaultCaptureLog.w(TAG, "unhideTrackerItem: failed to update tracker ${item.id}")
                        return@launch
                    }
                }
                HiddenTrackerItemType.GROUP -> {
                    val group = when (val loadResult = groupManagementRepository.loadGroup(item.id)) {
                        is RepositoryResult.Success -> loadResult.data
                        is RepositoryResult.Failure -> {
                            GeoVaultCaptureLog.w(TAG, "unhideTrackerItem: failed to load group ${item.id}")
                            return@launch
                        }
                    }
                    val result = groupManagementRepository.patchGroup(
                        groupId = item.id,
                        request = GroupSharingSettingsPolicy.buildUnhidePatch(group)
                    )
                    if (result is RepositoryResult.Failure) {
                        GeoVaultCaptureLog.w(TAG, "unhideTrackerItem: failed to update group ${item.id}")
                        return@launch
                    }
                }
            }
            _state.update { current ->
                current.copy(
                    hiddenTrackerItems = current.hiddenTrackerItems.filterNot {
                        it.id == item.id && it.type == item.type
                    }
                )
            }
        }
    }

    fun unhideAllTrackerItems() {
        viewModelScope.launch {
            _state.update { it.copy(isHiddenTrackerItemsLoading = true) }
            when (val result = trackerManagementRepository.clearHiddenItems(SHOW_ALL_CLEAR_TARGETS)) {
                is RepositoryResult.Success -> {
                    refreshHiddenTrackerItems()
                }
                is RepositoryResult.Failure -> {
                    GeoVaultCaptureLog.w(TAG, "unhideAllTrackerItems: failed to clear hidden items")
                    _state.update { it.copy(isHiddenTrackerItemsLoading = false) }
                }
            }
        }
    }

    private fun refreshMeasurementDefaults() {
        _state.update {
            it.copy(usesImperialUnits = UnitUtils.usesImperialUnitsDefault(appContext))
        }
    }

    private fun enforceMotionSensorSupport() {
        val available = isSignificantMotionSensorAvailable()
        _state.update { it.copy(significantMotionSensorAvailable = available) }
        if (!available) {
            trackerSettingsRepository.setSignificantDataOnly(false)
        }
    }

    private fun isSignificantMotionSensorAvailable(): Boolean {
        val manager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        return manager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) != null
    }

}
