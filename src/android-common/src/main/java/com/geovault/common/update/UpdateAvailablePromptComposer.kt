package com.geovault.common.update

import com.geovault.common.ui.snackbar.GeoVaultSnackbarAction
import com.geovault.common.ui.snackbar.GeoVaultSnackbarModel

object UpdateAvailablePromptComposer {
    const val ACTION_OPEN_UPDATE_DETAILS: String = "open_update_details"
    private const val SNACKBAR_ID_UPDATE: String = "update-available"

    fun snackbarModelOrNull(
        result: VersionCheckResult,
        message: String,
        detailsActionLabel: String,
    ): GeoVaultSnackbarModel? {
        if (result !is VersionCheckResult.UpdateAvailable) return null
        return modelForUpdateAvailable(result, message, detailsActionLabel)
    }

    fun modelForUpdateAvailable(
        result: VersionCheckResult.UpdateAvailable,
        message: String,
        detailsActionLabel: String,
    ): GeoVaultSnackbarModel {
        return GeoVaultSnackbarModel(
            id = SNACKBAR_ID_UPDATE,
            message = message,
            action = GeoVaultSnackbarAction(
                label = detailsActionLabel,
                actionId = ACTION_OPEN_UPDATE_DETAILS
            )
        )
    }
}
