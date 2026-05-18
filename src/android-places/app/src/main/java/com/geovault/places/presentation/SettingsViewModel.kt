package com.geovault.places.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data object SettingsState

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(SettingsState)
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    fun initialize() = Unit
    fun onHostResumed() = Unit
}
