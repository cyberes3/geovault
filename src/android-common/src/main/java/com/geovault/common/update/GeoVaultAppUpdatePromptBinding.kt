package com.geovault.common.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Owns the repeated ViewModel wiring for [GeoVaultAppUpdateCoordinator]: collect the prompt,
 * launch on first auth, reset on sign-out, and dismiss.
 */
class GeoVaultAppUpdatePromptBinding(
    private val coordinator: GeoVaultAppUpdateCoordinator,
) {
    val promptState: StateFlow<UpdatePromptState> = coordinator.promptState

    fun collect(scope: CoroutineScope, onUpdateChanged: (VersionCheckResult.UpdateAvailable?) -> Unit) {
        scope.launch {
            coordinator.promptState.collect { prompt ->
                onUpdateChanged(prompt.updateOrNull())
            }
        }
    }

    fun onAuthenticated(scope: CoroutineScope) {
        coordinator.launchIfNeeded(scope)
    }

    fun onSignedOut() {
        coordinator.reset()
    }

    fun dismissPrompt() {
        coordinator.dismissPrompt()
    }
}
