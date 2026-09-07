package com.geovault.tracker.presentation

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import com.geovault.common.logging.GeoVaultCaptureLog
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.common.net.GeoVaultApiFailure
import com.geovault.common.util.UnitUtils
import com.geovault.tracker.R
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
            val trackers = try {
                trackerManagementRepository.loadTrackers(forceRefresh = true)
            } catch (e: GeoVaultApiFailure) {
                GeoVaultCaptureLog.w(TAG, "refreshHiddenTrackerItems: failed to load trackers", e)
                _state.update { it.copy(isHiddenTrackerItemsLoading = false) }
                return@launch
            }
            val groups = try {
                groupManagementRepository.loadGroups(forceRefresh = true)
            } catch (e: GeoVaultApiFailure) {
                GeoVaultCaptureLog.w(TAG, "refreshHiddenTrackerItems: failed to load groups", e)
                _state.update { it.copy(isHiddenTrackerItemsLoading = false) }
                return@launch
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
                    val tracker = try {
                        trackerManagementRepository.loadTracker(item.id)
                    } catch (e: GeoVaultApiFailure) {
                        GeoVaultCaptureLog.w(TAG, "unhideTrackerItem: failed to load tracker ${item.id}", e)
                        return@launch
                    }
                    try {
                        trackerManagementRepository.updateTrackerSettings(
                            trackerId = item.id,
                            request = TrackerSharingSettingsPolicy.buildPreservingSettingsRequest(
                                tracker = tracker,
                                hidden = false,
                            )
                        )
                    } catch (e: GeoVaultApiFailure) {
                        GeoVaultCaptureLog.w(TAG, "unhideTrackerItem: failed to update tracker ${item.id}", e)
                        return@launch
                    }
                }
                HiddenTrackerItemType.GROUP -> {
                    val group = try {
                        groupManagementRepository.loadGroup(item.id)
                    } catch (e: GeoVaultApiFailure) {
                        GeoVaultCaptureLog.w(TAG, "unhideTrackerItem: failed to load group ${item.id}", e)
                        return@launch
                    }
                    try {
                        groupManagementRepository.patchGroup(
                            groupId = item.id,
                            request = GroupSharingSettingsPolicy.buildUnhidePatch(group)
                        )
                    } catch (e: GeoVaultApiFailure) {
                        GeoVaultCaptureLog.w(TAG, "unhideTrackerItem: failed to update group ${item.id}", e)
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
            try {
                trackerManagementRepository.clearHiddenItems(SHOW_ALL_CLEAR_TARGETS)
                refreshHiddenTrackerItems()
            } catch (e: GeoVaultApiFailure) {
                GeoVaultCaptureLog.w(TAG, "unhideAllTrackerItems: failed to clear hidden items", e)
                _state.update { it.copy(isHiddenTrackerItemsLoading = false) }
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
