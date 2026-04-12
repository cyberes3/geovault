package com.geovault.common.update

import android.app.Application
import android.content.Context
import com.geovault.common.ui.snackbar.GeoVaultSnackbarModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs the shared GeoVault release check at most once per [reset] cycle (typically one
 * signed-in session). Call [launchIfNeeded] after auth succeeds; call [reset] on sign-out.
 *
 * [checkIfDue] defaults to [AppVersionChecker.checkForUpdateIfDue]; supply a stub in tests.
 */
class GeoVaultVersionCheckSession(
    private val application: Application,
    private val rateLimitKey: String,
    private val releaseWorkerAppName: String,
    private val localFullCommitSha: () -> String,
    private val checkIfDue: (Context, String, VersionCheckRequest) -> VersionCheckResult =
        { context, key, request ->
            AppVersionChecker().checkForUpdateIfDue(
                context = context,
                rateLimitKey = key,
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
        onUpdatePrompt: (GeoVaultSnackbarModel, String) -> Unit,
    ) {
        if (launchedThisSession) return
        launchedThisSession = true
        val appContext = application.applicationContext
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                checkIfDue(
                    appContext,
                    rateLimitKey,
                    VersionCheckRequest(
                        appName = releaseWorkerAppName,
                        localFullCommitSha = localFullCommitSha().trim().lowercase(),
                    ),
                )
            }
            val (model, url) = VersionCheckSnackbarPresenter.snackbarAndReleaseUrl(result)
            if (model != null && url != null) {
                onUpdatePrompt(model, url)
            }
        }
    }
}
