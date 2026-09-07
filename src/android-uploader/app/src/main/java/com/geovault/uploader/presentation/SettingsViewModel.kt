package com.geovault.uploader.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.uploader.data.UploaderSettingsStore
import com.geovault.uploader.di.UploaderAppServices
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsState(
    val suffixEnabled: Boolean = true,
)

class SettingsViewModel(
    application: Application,
    private val settingsStore: UploaderSettingsStore,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application,
        UploaderAppServices.from(application).settingsStore(),
    )

    val state: StateFlow<SettingsState> = settingsStore.settings
        .map { settings -> SettingsState(suffixEnabled = settings.suffixEnabled) }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            SettingsState(suffixEnabled = settingsStore.snapshot().suffixEnabled),
        )

    fun onSuffixChanged(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setSuffixEnabled(enabled)
        }
    }
}
