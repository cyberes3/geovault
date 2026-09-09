package com.geovault.tracker.ui

import com.geovault.common.ui.snackbar.GeoVaultSnackbarModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class TrackerUiEffects {
    private val _effects = MutableSharedFlow<TrackerUiEffect>(extraBufferCapacity = 16)
    val effects: SharedFlow<TrackerUiEffect> = _effects.asSharedFlow()

    fun emit(effect: TrackerUiEffect) {
        _effects.tryEmit(effect)
    }

    fun emitMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        emit(
            TrackerUiEffect.Snackbar(
                GeoVaultSnackbarModel(
                    id = "tracker-host-${trimmed.hashCode()}",
                    message = trimmed,
                )
            )
        )
    }
}
