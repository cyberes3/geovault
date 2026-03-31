package com.geovault.common.update

data class UpdatePrompt(
    val message: String,
    val actionLabel: String,
    val releaseUrl: String
)

object VersionCheckSnackbarHelper {
    private const val ACTION_LABEL = "Open"

    fun buildPrompt(result: VersionCheckResult): UpdatePrompt? {
        if (result !is VersionCheckResult.UpdateAvailable) return null
        return UpdatePrompt(
            message = "A newer ${result.appName} version is available (${result.versionLabel}).",
            actionLabel = ACTION_LABEL,
            releaseUrl = result.releaseUrl
        )
    }
}
