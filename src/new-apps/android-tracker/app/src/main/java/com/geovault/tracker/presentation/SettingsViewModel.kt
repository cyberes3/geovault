package com.geovault.tracker.presentation

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.common.AppResetFlow
import com.geovault.common.UnitUtils
import com.geovault.common.auth.CommonInitialAuthController
import com.geovault.tracker.AppError
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.R
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.SelectedTrackerManager
import com.geovault.tracker.SelectedTrackerPrefs
import com.geovault.tracker.Tracker
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
    val selectedTrackerId: String = "",
    val selectedTrackerName: String = "",
    val selectableTrackers: List<SelectableTracker> = emptyList(),
    val isSelectableTrackersLoading: Boolean = false,
    val hiddenMapItems: List<HiddenMapItem> = emptyList(),
    val isHiddenMapItemsUpdating: Boolean = false,
    val usesImperialUnits: Boolean = false,
    val significantMotionSensorAvailable: Boolean = true,
)

data class SelectableTracker(
    val id: String,
    val name: String,
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
        refreshSelectableTrackers()
        refreshHiddenMapItems()
    }

    fun onHostResumed() {
        enforceMotionSensorSupport()
        refreshAuthState()
        refreshSelectableTrackers()
        refreshHiddenMapItems()
    }

    fun onServerUrlChanged(url: String) {
        _state.update { it.copy(serverUrl = url) }
        authController.setServerUrl(url)
    }

    fun connect() {
        _state.update { it.copy(isConnecting = true, infoMessage = "Connecting to server...") }
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
                it.copy(infoMessage = appContext.getString(R.string.settings_motion_sensor_unavailable))
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

    fun refreshSelectableTrackers() {
        viewModelScope.launch {
            _state.update { it.copy(isSelectableTrackersLoading = true) }
            when (val loaded = trackerManagementRepository.loadTrackers(forceRefresh = true)) {
                is RepositoryResult.Success -> {
                    val selectedId = SelectedTrackerPrefs.selectedTrackerId(appContext)
                    val selectedName = SelectedTrackerPrefs.selectedTrackerName(appContext)
                    _state.update {
                        it.copy(
                            isSelectableTrackersLoading = false,
                            selectedTrackerId = selectedId,
                            selectedTrackerName = selectedName,
                            selectableTrackers = loaded.data
                                .sortedBy { tracker -> tracker.name.lowercase() }
                                .map { tracker -> SelectableTracker(id = tracker.id, name = tracker.name) }
                        )
                    }
                }
                is RepositoryResult.Failure -> {
                    _state.update {
                        it.copy(
                            isSelectableTrackersLoading = false,
                            infoMessage = appErrorMessage(loaded.error)
                        )
                    }
                }
            }
        }
    }

    fun setSelectedTracker(trackerId: String) {
        val selected = _state.value.selectableTrackers.firstOrNull { it.id == trackerId } ?: return
        SelectedTrackerManager.setSelectedTracker(
            context = appContext,
            trackerId = selected.id,
            trackerName = selected.name,
            restartTrackingIfRunning = true
        )
        _state.update {
            it.copy(
                selectedTrackerId = selected.id,
                selectedTrackerName = selected.name
            )
        }
    }

    fun clearSelectedTracker() {
        SelectedTrackerManager.clearSelectedTrackerAndInvalidateCaches(appContext)
        _state.update {
            it.copy(
                selectedTrackerId = "",
                selectedTrackerName = ""
            )
        }
    }

    fun refreshHiddenMapItems() {
        viewModelScope.launch {
            refreshHiddenMapItemsFromServer(forceRefresh = true, clearMessage = false)
        }
    }

    fun unhideMapItem(item: HiddenMapItem) {
        viewModelScope.launch {
            _state.update { it.copy(isHiddenMapItemsUpdating = true, infoMessage = null) }
            val visibility = when (val result = trackerManagementRepository.loadMapVisibility(forceRefresh = false)) {
                is RepositoryResult.Success -> result.data
                is RepositoryResult.Failure -> {
                    _state.update {
                        it.copy(
                            isHiddenMapItemsUpdating = false,
                            infoMessage = appErrorMessage(result.error)
                        )
                    }
                    return@launch
                }
            }
            val request = HiddenMapItemsCoordinator.buildUnhideItemRequest(visibility, item)
            when (val patch = trackerManagementRepository.patchMapVisibility(request)) {
                is RepositoryResult.Success -> {
                    refreshHiddenMapItemsFromServer(forceRefresh = false, clearMessage = false)
                }
                is RepositoryResult.Failure -> {
                    _state.update {
                        it.copy(
                            isHiddenMapItemsUpdating = false,
                            infoMessage = appErrorMessage(patch.error)
                        )
                    }
                }
            }
        }
    }

    fun unhideAllMapItems() {
        viewModelScope.launch {
            _state.update { it.copy(isHiddenMapItemsUpdating = true, infoMessage = null) }
            when (
                val patch = trackerManagementRepository.patchMapVisibility(
                    HiddenMapItemsCoordinator.buildUnhideAllRequest()
                )
            ) {
                is RepositoryResult.Success -> {
                    refreshHiddenMapItemsFromServer(forceRefresh = false, clearMessage = false)
                }
                is RepositoryResult.Failure -> {
                    _state.update {
                        it.copy(
                            isHiddenMapItemsUpdating = false,
                            infoMessage = appErrorMessage(patch.error)
                        )
                    }
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
                selectedTrackerId = SelectedTrackerPrefs.selectedTrackerId(appContext),
                selectedTrackerName = SelectedTrackerPrefs.selectedTrackerName(appContext),
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

    private fun appErrorMessage(error: AppError): String {
        return when (error) {
            AppError.MissingServerUrl -> appContext.getString(R.string.trackers_error_missing_server)
            AppError.Network -> appContext.getString(R.string.trackers_error_network)
            AppError.Unauthorized -> appContext.getString(R.string.trackers_error_unauthorized)
            AppError.NotFound -> appContext.getString(R.string.trackers_error_not_found)
            is AppError.Server -> appContext.getString(R.string.trackers_error_server, error.code)
            is AppError.Validation -> error.message?.takeIf { it.isNotBlank() }
                ?: appContext.getString(R.string.trackers_error_validation)
            AppError.Unknown -> appContext.getString(R.string.trackers_error_unknown)
        }
    }

    private suspend fun refreshHiddenMapItemsFromServer(
        forceRefresh: Boolean,
        clearMessage: Boolean,
    ) {
        when (
            val snapshotResult = HiddenMapItemsCoordinator.loadSnapshot(
                forceRefresh = forceRefresh,
                loadMapVisibility = { force -> trackerManagementRepository.loadMapVisibility(forceRefresh = force) },
                loadTrackers = { force -> trackerManagementRepository.loadTrackers(forceRefresh = force) },
                loadGroups = { force -> groupManagementRepository.loadGroups(forceRefresh = force) }
            )
        ) {
            is RepositoryResult.Success -> {
                val warningMessage = snapshotResult.data.warning?.let(::appErrorMessage)
                _state.update {
                    it.copy(
                        isHiddenMapItemsUpdating = false,
                        hiddenMapItems = HiddenMapItemsCoordinator.buildHiddenItems(snapshotResult.data),
                        infoMessage = if (clearMessage) null else warningMessage
                    )
                }
            }
            is RepositoryResult.Failure -> {
                _state.update {
                    it.copy(
                        isHiddenMapItemsUpdating = false,
                        infoMessage = appErrorMessage(snapshotResult.error)
                    )
                }
            }
        }
    }
}
