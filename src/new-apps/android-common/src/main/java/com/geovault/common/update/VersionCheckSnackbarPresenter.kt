package com.geovault.common.update

import android.util.Log
import com.geovault.common.ui.snackbar.GeoVaultSnackbarModel

object VersionCheckSnackbarPresenter {

    /**
     * Maps a version-check result to an optional snackbar plus release URL for the Open action.
     * Applies the same logging semantics as the previous View snackbar `showIfUpdateAvailable` flow.
     */
    fun snackbarAndReleaseUrl(result: VersionCheckResult): Pair<GeoVaultSnackbarModel?, String?> {
        when (result) {
            is VersionCheckResult.CheckFailed -> {
                Log.i(
                    UpdateCheckLog.TAG,
                    "snackbar: version check unavailable (${result.detail}); no user prompt shown"
                )
                return Pair(null, null)
            }

            is VersionCheckResult.UpdateAvailable -> {
                Log.i(
                    UpdateCheckLog.TAG,
                    "snackbar: showing update prompt for ${result.appName} ${result.versionLabel}"
                )
                val model = UpdateAvailablePromptComposer.modelForUpdateAvailable(result)
                return Pair(model, result.releaseUrl)
            }

            else -> return Pair(null, null)
        }
    }
}
