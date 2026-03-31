package com.geovault.common.update

import com.geovault.common.ui.snackbar.GeoVaultSnackbarAction
import com.geovault.common.ui.snackbar.GeoVaultSnackbarModel

object UpdateAvailablePromptComposer {
    const val ACTION_OPEN_RELEASE: String = "open_release"
    private const val SNACKBAR_ID_UPDATE: String = "update-available"

    fun snackbarModelOrNull(result: VersionCheckResult): GeoVaultSnackbarModel? {
        if (result !is VersionCheckResult.UpdateAvailable) return null
        return modelForUpdateAvailable(result)
    }

    fun modelForUpdateAvailable(result: VersionCheckResult.UpdateAvailable): GeoVaultSnackbarModel {
        return GeoVaultSnackbarModel(
            id = SNACKBAR_ID_UPDATE,
            message = "A newer ${result.appName} version is available (${result.versionLabel}).",
            action = GeoVaultSnackbarAction(
                label = "Open",
                actionId = ACTION_OPEN_RELEASE
            )
        )
    }
}
