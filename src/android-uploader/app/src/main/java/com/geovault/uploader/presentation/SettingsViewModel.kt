package com.geovault.uploader.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.uploader.di.UploaderAppServices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class SettingsState(
    val suffixEnabled: Boolean = true,
)

class SettingsViewModel(
    application: Application,
    services: UploaderAppServices,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application,
        UploaderAppServices.from(application)
    )
    private val prefs = services.uploaderPreferences()

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            prefs.settings.collect { settings ->
                _state.value = _state.value.copy(
                    suffixEnabled = settings.suffixEnabled
                )
            }
        }
    }

    fun initialize() = Unit

    fun onHostResumed() = Unit

    fun onSuffixChanged(enabled: Boolean) {
        _state.value = _state.value.copy(suffixEnabled = enabled)
        viewModelScope.launch {
            prefs.setSuffixEnabled(enabled)
        }
    }
}
