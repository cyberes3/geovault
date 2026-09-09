package com.geovault.tracker.ui

import com.geovault.common.ui.snackbar.GeoVaultSnackbarModel

sealed interface TrackerUiEffect {
    data class Snackbar(val model: GeoVaultSnackbarModel) : TrackerUiEffect
    data class Message(val text: String) : TrackerUiEffect
}
