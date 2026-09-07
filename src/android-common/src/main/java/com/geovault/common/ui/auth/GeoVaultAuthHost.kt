package com.geovault.common.ui.auth

import android.content.Intent
import androidx.activity.ComponentActivity
import com.geovault.common.auth.GeoVaultAccountViewModel
import com.geovault.common.auth.GeoVaultAuthExtras
import com.geovault.common.ui.splash.GeoVaultSplashScreen
import com.geovault.common.ui.system.GeoVaultSystemBars
import kotlinx.coroutines.flow.StateFlow

/**
 * Shared MainActivity auth lifecycle: splash, chrome, OAuth error extras, and session resume.
 *
 * Call [installSplash] before `super.onCreate`. Call [onCreate] after it. Forward [onNewIntent],
 * [onResume], and [onStop] from the host activity.
 */
object GeoVaultAuthHost {
    fun installSplash(activity: ComponentActivity, ready: StateFlow<Boolean>) {
        GeoVaultSplashScreen.install(activity, ready)
    }

    fun onCreate(activity: ComponentActivity, accountViewModel: GeoVaultAccountViewModel) {
        GeoVaultSystemBars.applyAppChrome(activity)
        accountViewModel.initialize()
        consumeOauthError(activity.intent, accountViewModel)
    }

    fun onNewIntent(intent: Intent, accountViewModel: GeoVaultAccountViewModel) {
        consumeOauthError(intent, accountViewModel)
    }

    fun onResume(accountViewModel: GeoVaultAccountViewModel) {
        accountViewModel.onHostResumed()
    }

    fun onStop(accountViewModel: GeoVaultAccountViewModel) {
        accountViewModel.onOauthUrlConsumed()
    }

    fun consumeOauthError(intent: Intent?, accountViewModel: GeoVaultAccountViewModel) {
        val error = intent?.getStringExtra(GeoVaultAuthExtras.OAUTH_ERROR_EXTRA_KEY)
            ?.takeIf { it.isNotBlank() }
            ?: return
        accountViewModel.showExternalError(error)
        intent.removeExtra(GeoVaultAuthExtras.OAUTH_ERROR_EXTRA_KEY)
    }
}
