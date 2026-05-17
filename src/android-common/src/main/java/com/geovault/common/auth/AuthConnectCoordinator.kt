package com.geovault.common.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Owns in-flight OAuth preparation: cancel/restart on re-tap, drop stale async results.
 * ViewModels map [CommonInitialAuthController.OAuthPreparationResult] to their own UI state.
 */
class AuthConnectCoordinator(
    private val scope: CoroutineScope,
    private val controller: CommonInitialAuthController,
) {
    private var activeJob: Job? = null
    private var generation: Int = 0

    fun launch(
        rawServerUrl: String,
        onConnecting: () -> Unit,
        onResult: (CommonInitialAuthController.OAuthPreparationResult) -> Unit,
    ) {
        activeJob?.cancel()
        val launchGeneration = ++generation
        onConnecting()
        activeJob = scope.launch {
            try {
                val result = controller.prepareOAuthConnection(rawServerUrl)
                if (launchGeneration == generation) {
                    onResult(result)
                }
            } catch (e: CancellationException) {
                throw e
            }
        }
    }
}
