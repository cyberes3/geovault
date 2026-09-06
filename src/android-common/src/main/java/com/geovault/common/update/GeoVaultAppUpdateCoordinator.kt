package com.geovault.common.update

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Auth-gated, once-per-[reset] version check. [promptState] is the only emission surface:
 * a cached offer is published without clearing first, a failed live check never emits
 * [UpdatePromptState.Hidden], and only a confirmed [VersionCheckResult.UpToDate] (or
 * [reset] / [dismissPrompt]) hides the prompt.
 */
class GeoVaultAppUpdateCoordinator(
    private val cacheKey: String,
    private val releaseWorkerAppName: String,
    private val localFullCommitSha: () -> String,
    private val isLoggedIn: () -> Boolean,
    private val peekCachedUpdate: (cacheKey: String, localSha: String) -> VersionCheckResult.UpdateAvailable?,
    private val checkForUpdate: (cacheKey: String, request: VersionCheckRequest) -> VersionCheckResult,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val _promptState = MutableStateFlow<UpdatePromptState>(UpdatePromptState.Hidden)
    val promptState: StateFlow<UpdatePromptState> = _promptState.asStateFlow()

    @Volatile
    private var launchedThisSession: Boolean = false

    fun reset() {
        launchedThisSession = false
        _promptState.value = UpdatePromptState.Hidden
    }

    fun dismissPrompt() {
        _promptState.value = UpdatePromptState.Hidden
    }

    fun launchIfNeeded(scope: CoroutineScope) {
        if (!isLoggedIn()) return
        if (launchedThisSession) return
        launchedThisSession = true
        val normalizedLocalSha = localFullCommitSha().trim().lowercase()
        scope.launch {
            val cached = withContext(ioDispatcher) {
                peekCachedUpdate(cacheKey, normalizedLocalSha)
            }
            if (cached != null) {
                _promptState.value = UpdatePromptState.Available(cached)
            }
            val result = withContext(ioDispatcher) {
                checkForUpdate(
                    cacheKey,
                    VersionCheckRequest(
                        appName = releaseWorkerAppName,
                        localFullCommitSha = normalizedLocalSha,
                    ),
                )
            }
            applyLiveResult(result)
        }
    }

    private fun applyLiveResult(result: VersionCheckResult) {
        when (result) {
            is VersionCheckResult.UpdateAvailable -> {
                Log.i(
                    UpdateCheckLog.TAG,
                    "update prompt: showing update prompt for ${result.appName} ${result.versionLabel}",
                )
                _promptState.value = UpdatePromptState.Available(result)
            }

            is VersionCheckResult.UpToDate -> {
                _promptState.value = UpdatePromptState.Hidden
            }

            is VersionCheckResult.CheckFailed -> {
                Log.i(
                    UpdateCheckLog.TAG,
                    "update prompt: version check unavailable (${result.detail}); keeping current prompt",
                )
            }

            is VersionCheckResult.NoMatch -> Unit
        }
    }
}
