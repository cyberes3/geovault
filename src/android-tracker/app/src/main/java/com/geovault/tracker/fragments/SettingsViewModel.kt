package com.geovault.tracker.fragments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.tracker.settings.TrackerSettings
import com.geovault.tracker.settings.TrackerSettingsLoadState
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
    val phase: SettingsPhase = SettingsPhase.Syncing
)

enum class SettingsPhase {
    Ready,
    Syncing
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: TrackerSettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            settings = settingsRepository.getState().settings,
            phase = if (settingsRepository.getState().isReady) SettingsPhase.Ready else SettingsPhase.Syncing
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.observeState().collectLatest { snapshot ->
                val phase = when (snapshot.loadState) {
                    TrackerSettingsLoadState.Ready -> SettingsPhase.Ready
                    TrackerSettingsLoadState.Loading,
                    TrackerSettingsLoadState.Error -> SettingsPhase.Syncing
                }
                _uiState.value = SettingsUiState(
                    settings = snapshot.settings,
                    phase = phase
                )
            }
        }
    }

    fun setSendExtendedData(enabled: Boolean) {
        settingsRepository.setSendExtendedData(enabled)
    }

    fun setSignificantDataOnly(enabled: Boolean) {
        settingsRepository.setSignificantDataOnly(enabled)
    }

    fun setStartOnBoot(enabled: Boolean) {
        settingsRepository.setStartOnBoot(enabled)
    }

    fun setStartTrackingOnLaunch(enabled: Boolean) {
        settingsRepository.setStartTrackingOnLaunch(enabled)
    }

    fun setKeepScreenOnWhileViewingMap(enabled: Boolean) {
        settingsRepository.setKeepScreenOnWhileViewingMap(enabled)
    }

    fun setAutoTrackingMode(enabled: Boolean) {
        settingsRepository.setAutoTrackingMode(enabled)
    }

    fun setTrackingProfile(profile: TrackerTrackingProfile) {
        settingsRepository.setTrackingProfile(profile)
    }

    fun setLoggingIntervalSec(value: Long) {
        settingsRepository.setLoggingIntervalSec(value)
    }

    fun setDistanceFilterMeters(value: Float) {
        settingsRepository.setDistanceFilterMeters(value)
    }

    fun setAccuracyFilterMeters(value: Float) {
        settingsRepository.setAccuracyFilterMeters(value)
    }

    fun setLowAccuracyFallbackEnabled(enabled: Boolean) =
        run {
            settingsRepository.setLowAccuracyFallbackEnabled(enabled)
        }

    fun setLowAccuracyFallbackTimeoutSec(value: Long) =
        run {
            settingsRepository.setLowAccuracyFallbackTimeoutSec(value)
        }

    fun dumpDebugState(reason: String) {
        settingsRepository.dumpDebugState(reason)
    }
}
