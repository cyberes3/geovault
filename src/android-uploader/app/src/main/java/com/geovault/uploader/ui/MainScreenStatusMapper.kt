package com.geovault.uploader.ui

import com.geovault.common.ui.model.GeoVaultActionRenderModel
import com.geovault.common.ui.model.GeoVaultStatusRenderModel
import com.geovault.common.ui.model.GeoVaultStatusVisualState
import com.geovault.uploader.data.ValidationOutcome
import com.geovault.uploader.presentation.HomeScreenState

internal object MainScreenStatusMapper {
    fun toValidationStatusModel(state: HomeScreenState): GeoVaultStatusRenderModel {
        val visualState = when (state.validationOutcome) {
            ValidationOutcome.Loading -> GeoVaultStatusVisualState.Loading
            ValidationOutcome.Success -> GeoVaultStatusVisualState.Success
            ValidationOutcome.Error -> GeoVaultStatusVisualState.Error
            ValidationOutcome.Info -> GeoVaultStatusVisualState.Info
        }
        val title = when {
            visualState == GeoVaultStatusVisualState.Success -> null
            state.validationTitle.isBlank() -> "Validation"
            else -> state.validationTitle
        }
        val primaryAction = if (visualState == GeoVaultStatusVisualState.Success && !state.isValidationLoading) {
            GeoVaultActionRenderModel(label = "Choose File")
        } else {
            null
        }
        val secondaryAction = if (
            visualState == GeoVaultStatusVisualState.Info ||
            visualState == GeoVaultStatusVisualState.Error
        ) {
            GeoVaultActionRenderModel(label = "Settings")
        } else {
            null
        }
        return GeoVaultStatusRenderModel(
            visualState = visualState,
            title = title,
            message = state.validationMessage,
            primaryAction = primaryAction,
            secondaryAction = secondaryAction,
        )
    }
}
