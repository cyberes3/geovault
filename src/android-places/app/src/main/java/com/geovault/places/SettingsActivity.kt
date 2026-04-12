package com.geovault.places

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
import com.geovault.places.presentation.SettingsViewModel
import com.geovault.places.ui.SettingsScreen

class SettingsActivity : ComponentActivity() {
    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GeoVaultSystemBars.applyAppChrome(this)
        viewModel.initialize()
        setContent {
            GeoVaultTheme {
                val state by viewModel.state.collectAsState()
                LaunchedEffect(state.oauthUrl) {
                    state.oauthUrl?.let { GeovaultAuthManager.launchOAuthInBrowser(this@SettingsActivity, it) }
                }
                SettingsScreen(
                    state = state,
                    onServerUrlChanged = viewModel::onServerUrlChanged,
                    onConnect = viewModel::connect,
                    onDisconnect = { viewModel.disconnect(MainActivity::class.java) },
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
