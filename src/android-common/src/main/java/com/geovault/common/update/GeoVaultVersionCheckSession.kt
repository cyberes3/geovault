package com.geovault.common.update

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs the shared GeoVault release check at most once per [reset] cycle (typically one
 * signed-in session, and naturally once per app process since a fresh instance is created
 * on cold start). Call [launchIfNeeded] after auth succeeds; call [reset] on sign-out.
 *
 * [launchIfNeeded] shows any cached "out of date" result instantly (no network round-trip),
 * then always runs a live check in the background and reports its (possibly different) result,
 * so the prompt clears itself once the live check confirms the app is up to date.
 *
 * [checkIfDue] defaults to [AppVersionChecker.checkForUpdateIfDue]; [peekCachedUpdate] defaults
 * to [AppVersionChecker.peekCachedUpdate]. Supply stubs in tests.
 */
class GeoVaultVersionCheckSession(
    private val application: Application,
    private val cacheKey: String,
    private val releaseWorkerAppName: String,
    private val localFullCommitSha: () -> String,
    private val peekCachedUpdate: (Context, String, String) -> VersionCheckResult.UpdateAvailable? =
        { context, key, localSha ->
            AppVersionChecker().peekCachedUpdate(
                context = context,
                cacheKey = key,
                localFullCommitSha = localSha,
            )
        },
    private val checkIfDue: (Context, String, VersionCheckRequest) -> VersionCheckResult =
        { context, key, request ->
            AppVersionChecker().checkForUpdateIfDue(
                context = context,
                cacheKey = key,
                request = request,
            )
        },
) {

    private var launchedThisSession: Boolean = false

    fun reset() {
        launchedThisSession = false
    }

    fun launchIfNeeded(
        scope: CoroutineScope,
        onUpdatePrompt: (VersionCheckResult.UpdateAvailable?) -> Unit,
    ) {
        if (launchedThisSession) return
        launchedThisSession = true
        val appContext = application.applicationContext
        val normalizedLocalSha = localFullCommitSha().trim().lowercase()
        scope.launch {
            val cached = withContext(Dispatchers.IO) {
                peekCachedUpdate(appContext, cacheKey, normalizedLocalSha)
            }
            if (cached != null) {
                onUpdatePrompt(cached)
            }
            val result = withContext(Dispatchers.IO) {
                checkIfDue(
                    appContext,
                    cacheKey,
                    VersionCheckRequest(
                        appName = releaseWorkerAppName,
                        localFullCommitSha = normalizedLocalSha,
                    ),
                )
            }
            onUpdatePrompt(VersionCheckSnackbarPresenter.updateAvailableOrNull(result))
        }
    }
}
