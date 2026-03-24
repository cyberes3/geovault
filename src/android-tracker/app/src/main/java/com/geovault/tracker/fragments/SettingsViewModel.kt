package com.geovault.tracker.fragments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.tracker.settings.TrackerSettings
import com.geovault.tracker.settings.TrackerSettingsRepository
import com.geovault.tracker.settings.TrackerTrackingProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: TrackerSettings = TrackerSettings(),
    val phase: SettingsPhase = SettingsPhase.Ready,
    val pendingFieldKey: String? = null
)

enum class SettingsPhase {
    Ready,
    Syncing
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: TrackerSettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState(settingsRepository.getSettings()))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.observeSettings().collectLatest { settings ->
                _uiState.value = SettingsUiState(
                    settings = settings,
                    phase = SettingsPhase.Ready,
                    pendingFieldKey = null
                )
            }
        }
    }

    private fun beginSync(fieldKey: String) {
        _uiState.value = _uiState.value.copy(phase = SettingsPhase.Syncing, pendingFieldKey = fieldKey)
    }

    fun setSendExtendedData(enabled: Boolean) {
        beginSync("send_extended_data")
        settingsRepository.setSendExtendedData(enabled)
    }

    fun setSignificantDataOnly(enabled: Boolean) {
        beginSync("significant_data_only")
        settingsRepository.setSignificantDataOnly(enabled)
    }

    fun setStartOnBoot(enabled: Boolean) {
        beginSync("start_on_boot")
        settingsRepository.setStartOnBoot(enabled)
    }

    fun setResetTrackingIfKilled(enabled: Boolean) {
        beginSync("reset_tracking_if_killed")
        settingsRepository.setResetTrackingIfKilled(enabled)
    }

    fun setStartTrackingOnLaunch(enabled: Boolean) {
        beginSync("start_tracking_on_launch")
        settingsRepository.setStartTrackingOnLaunch(enabled)
    }

    fun setKeepScreenOnWhileViewingMap(enabled: Boolean) {
        beginSync("keep_screen_on_while_viewing_map")
        settingsRepository.setKeepScreenOnWhileViewingMap(enabled)
    }

    fun setAutoTrackingMode(enabled: Boolean) {
        beginSync("auto_tracking_mode")
        settingsRepository.setAutoTrackingMode(enabled)
    }

    fun setTrackingProfile(profile: TrackerTrackingProfile) {
        beginSync("tracking_profile")
        settingsRepository.setTrackingProfile(profile)
    }

    fun setLoggingIntervalSec(value: Long) {
        beginSync("logging_interval_sec")
        settingsRepository.setLoggingIntervalSec(value)
    }

    fun setDistanceFilterMeters(value: Float) {
        beginSync("distance_filter_meters")
        settingsRepository.setDistanceFilterMeters(value)
    }

    fun setAccuracyFilterMeters(value: Float) {
        beginSync("accuracy_filter_meters")
        settingsRepository.setAccuracyFilterMeters(value)
    }

    fun setLowAccuracyFallbackEnabled(enabled: Boolean) =
        run {
            beginSync("low_accuracy_fallback_enabled")
            settingsRepository.setLowAccuracyFallbackEnabled(enabled)
        }

    fun setLowAccuracyFallbackTimeoutSec(value: Long) =
        run {
            beginSync("low_accuracy_fallback_timeout_sec")
            settingsRepository.setLowAccuracyFallbackTimeoutSec(value)
        }
}
