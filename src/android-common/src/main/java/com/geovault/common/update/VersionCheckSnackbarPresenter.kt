package com.geovault.common.update

import android.util.Log

object VersionCheckSnackbarPresenter {

    /**
     * Returns [VersionCheckResult.UpdateAvailable] when the user should see an update prompt;
     * otherwise null. Applies the same logging semantics as the previous snackbar flow.
     */
    fun updateAvailableOrNull(result: VersionCheckResult): VersionCheckResult.UpdateAvailable? {
        when (result) {
            is VersionCheckResult.CheckFailed -> {
                Log.i(
                    UpdateCheckLog.TAG,
                    "update prompt: version check unavailable (${result.detail}); no user prompt shown"
                )
                return null
            }

            is VersionCheckResult.UpdateAvailable -> {
                Log.i(
                    UpdateCheckLog.TAG,
                    "update prompt: showing update prompt for ${result.appName} ${result.versionLabel}"
                )
                return result
            }

            else -> return null
        }
    }
}
