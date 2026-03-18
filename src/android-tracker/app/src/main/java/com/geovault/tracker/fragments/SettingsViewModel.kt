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
    val settings: TrackerSettings = TrackerSettings()
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: TrackerSettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState(settingsRepository.getSettings()))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.observeSettings().collectLatest { settings ->
                _uiState.value = SettingsUiState(settings = settings)
            }
        }
    }

    fun setSendExtendedData(enabled: Boolean) = settingsRepository.setSendExtendedData(enabled)

    fun setSignificantDataOnly(enabled: Boolean) = settingsRepository.setSignificantDataOnly(enabled)

    fun setStartOnBoot(enabled: Boolean) = settingsRepository.setStartOnBoot(enabled)

    fun setResetTrackingIfKilled(enabled: Boolean) = settingsRepository.setResetTrackingIfKilled(enabled)

    fun setStartTrackingOnLaunch(enabled: Boolean) = settingsRepository.setStartTrackingOnLaunch(enabled)

    fun setAutoTrackingMode(enabled: Boolean) = settingsRepository.setAutoTrackingMode(enabled)

    fun setTrackingProfile(profile: TrackerTrackingProfile) = settingsRepository.setTrackingProfile(profile)

    fun setLoggingIntervalSec(value: Long) = settingsRepository.setLoggingIntervalSec(value)

    fun setDistanceFilterMeters(value: Float) = settingsRepository.setDistanceFilterMeters(value)

    fun setAccuracyFilterMeters(value: Float) = settingsRepository.setAccuracyFilterMeters(value)
}
