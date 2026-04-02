package com.geovault.tracker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.ui.system.GeoVaultSystemBars
import com.geovault.common.ui.theme.GeoVaultTheme
import com.geovault.tracker.presentation.MainScreenViewModel
import com.geovault.tracker.ui.MainScreen

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OAUTH_ERROR = "oauth_error"
    }

    private val viewModel: MainScreenViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GeoVaultSystemBars.applyAppChrome(activity = this)
        viewModel.initialize()

        setContent {
            GeoVaultTheme {
                val state by viewModel.state.collectAsState()

                LaunchedEffect(state.oauthUrl) {
                    state.oauthUrl?.let {
                        GeovaultAuthManager.launchOAuthInBrowser(this@MainActivity, it)
                    }
                }

                LaunchedEffect(Unit) {
                    intent.getStringExtra(EXTRA_OAUTH_ERROR)?.let { error ->
                        viewModel.showExternalError(error)
                        intent?.removeExtra(EXTRA_OAUTH_ERROR)
                    }
                }
                MainScreen(
                    state = state,
                    onOpenSettings = { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) },
                    onAuthServerUrlChanged = viewModel::onAuthServerUrlChanged,
                    onAuthConnect = viewModel::connectAuth,
                    onClearInfoMessage = viewModel::clearInfoMessage,
                    onRequestStartTracking = viewModel::requestStartTracking,
                    onRequestStopTracking = viewModel::requestStopTracking,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onHostResumed()
    }

    override fun onStop() {
        super.onStop()
        viewModel.onOauthUrlConsumed()
    }
}
