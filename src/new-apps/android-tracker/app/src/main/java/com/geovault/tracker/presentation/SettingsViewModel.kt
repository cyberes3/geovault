package com.geovault.tracker.presentation

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.common.AppResetFlow
import com.geovault.common.UnitUtils
import com.geovault.common.auth.CommonInitialAuthController
import com.geovault.tracker.R
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.data.GroupManagementRepository
import com.geovault.tracker.data.TrackerManagementRepository
import com.geovault.tracker.settings.TrackerSettings
import com.geovault.tracker.settings.TrackerSettingsLoadState
import com.geovault.tracker.settings.TrackerSettingsRepository
import com.geovault.tracker.settings.TrackerTrackingProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsState(
    val serverUrl: String = "",
    val isLoggedIn: Boolean = false,
    val loggedInText: String = "",
    val isConnecting: Boolean = false,
    val infoMessage: String? = null,
    val oauthUrl: String? = null,
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
    private val authController: CommonInitialAuthController =
        TrackerAppServices.from(application).initialAuthController(),
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
        TrackerAppServices.from(application).initialAuthController(),
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
        refreshAuthState()
        refreshHiddenTrackerItems()
    }

    fun onHostResumed() {
        enforceMotionSensorSupport()
        refreshAuthState()
        refreshHiddenTrackerItems()
    }

    fun onServerUrlChanged(url: String) {
        _state.update { it.copy(serverUrl = url) }
        authController.setServerUrl(url)
    }

    fun connect() {
        _state.update { it.copy(isConnecting = true, infoMessage = null) }
        viewModelScope.launch {
            when (val result = authController.prepareOAuthConnection(_state.value.serverUrl)) {
                is CommonInitialAuthController.OAuthPreparationResult.Ready -> {
                    _state.update {
                        it.copy(
                            serverUrl = authController.getConfiguredServerUrlOrPeerDefault(),
                            oauthUrl = result.oauthUrl,
                            isConnecting = false,
                            infoMessage = null,
                        )
                    }
                }
                is CommonInitialAuthController.OAuthPreparationResult.InvalidServerUrl -> {
                    _state.update { it.copy(isConnecting = false, infoMessage = result.message) }
                }
                is CommonInitialAuthController.OAuthPreparationResult.UnreachableServer -> {
                    _state.update { it.copy(isConnecting = false, infoMessage = result.message) }
                }
            }
        }
    }

    fun onOauthUrlConsumed() {
        _state.update { it.copy(oauthUrl = null, isConnecting = false) }
    }

    fun disconnect(mainActivityClass: Class<*>) {
        viewModelScope.launch {
            authController.revokeCurrentSessionTokens()
            AppResetFlow.execute(
                context = appContext,
                reason = AppResetFlow.Reason.MANUAL_SIGN_OUT,
                mainActivityClass = mainActivityClass,
            )
        }
    }

    fun setTrackingProfile(profile: TrackerTrackingProfile) {
        trackerSettingsRepository.setTrackingProfile(profile)
    }

    fun setLoggingIntervalSecFromInput(raw: String) {
        val value = raw.toLongOrNull() ?: return
        trackerSettingsRepository.setLoggingIntervalSec(value)
    }

    fun setDistanceFilterMetersFromInput(raw: String) {
        val value = SettingsMeasurementPolicy.displayTextToMetersOrNull(
            raw = raw,
            usesImperial = _state.value.usesImperialUnits
        ) ?: return
        trackerSettingsRepository.setDistanceFilterMeters(value)
    }

    fun setAccuracyFilterMetersFromInput(raw: String) {
        val value = SettingsMeasurementPolicy.displayTextToMetersOrNull(
            raw = raw,
            usesImperial = _state.value.usesImperialUnits
        ) ?: return
        trackerSettingsRepository.setAccuracyFilterMeters(value)
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

    fun setAutoTrackingMode(enabled: Boolean) {
        trackerSettingsRepository.setAutoTrackingMode(enabled)
    }

    fun setKeepScreenOnWhileViewingMap(enabled: Boolean) {
        trackerSettingsRepository.setKeepScreenOnWhileViewingMap(enabled)
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
                    Log.w(TAG, "refreshHiddenTrackerItems: failed to load trackers")
                    _state.update { it.copy(isHiddenTrackerItemsLoading = false) }
                    return@launch
                }
            }
            val groups = when (val result = groupManagementRepository.loadGroups(forceRefresh = true)) {
                is RepositoryResult.Success -> result.data
                is RepositoryResult.Failure -> {
                    Log.w(TAG, "refreshHiddenTrackerItems: failed to load groups")
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
                            Log.w(TAG, "unhideTrackerItem: failed to load tracker ${item.id}")
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
                        Log.w(TAG, "unhideTrackerItem: failed to update tracker ${item.id}")
                        return@launch
                    }
                }
                HiddenTrackerItemType.GROUP -> {
                    val group = when (val loadResult = groupManagementRepository.loadGroup(item.id)) {
                        is RepositoryResult.Success -> loadResult.data
                        is RepositoryResult.Failure -> {
                            Log.w(TAG, "unhideTrackerItem: failed to load group ${item.id}")
                            return@launch
                        }
                    }
                    val result = groupManagementRepository.patchGroup(
                        groupId = item.id,
                        request = GroupSharingSettingsPolicy.buildUnhidePatch(group)
                    )
                    if (result is RepositoryResult.Failure) {
                        Log.w(TAG, "unhideTrackerItem: failed to update group ${item.id}")
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
                    Log.w(TAG, "unhideAllTrackerItems: failed to clear hidden items")
                    _state.update { it.copy(isHiddenTrackerItemsLoading = false) }
                }
            }
        }
    }

    private fun refreshAuthState() {
        val server = authController.getConfiguredServerUrlOrPeerDefault()
        val loggedIn = server.isNotBlank() && authController.isLoggedIn()
        val cachedEmail = authController.getCachedUserEmail().orEmpty()
        val motionSensorAvailable = isSignificantMotionSensorAvailable()
        _state.update {
            it.copy(
                serverUrl = server,
                isLoggedIn = loggedIn,
                usesImperialUnits = UnitUtils.usesImperialUnitsDefault(appContext),
                significantMotionSensorAvailable = motionSensorAvailable,
                loggedInText = if (loggedIn && cachedEmail.isNotBlank()) {
                    "Logged in as $cachedEmail"
                } else {
                    if (loggedIn) "Logged in" else ""
                },
            )
        }
        if (loggedIn && cachedEmail.isBlank()) {
            authController.fetchUserEmail { email ->
                if (!email.isNullOrBlank()) {
                    _state.update { current -> current.copy(loggedInText = "Logged in as $email") }
                }
            }
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
