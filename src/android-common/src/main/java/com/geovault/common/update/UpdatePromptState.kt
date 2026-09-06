package com.geovault.common.update

sealed interface UpdatePromptState {
    data object Hidden : UpdatePromptState

    data class Available(
        val update: VersionCheckResult.UpdateAvailable,
    ) : UpdatePromptState

    fun updateOrNull(): VersionCheckResult.UpdateAvailable? =
        (this as? Available)?.update
}
