package com.geovault.uploader

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
import com.geovault.uploader.presentation.SettingsViewModel
import com.geovault.uploader.ui.SettingsScreen

class SettingsActivity : ComponentActivity() {
    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GeoVaultSystemBars.applyAppChrome(activity = this)
        viewModel.initialize()
        setContent {
            GeoVaultTheme {
                val state by viewModel.state.collectAsState()
                LaunchedEffect(state.oauthUrl) {
                    val oauthUrl = state.oauthUrl
                    if (!oauthUrl.isNullOrBlank()) {
                        GeovaultAuthManager.launchOAuthInBrowser(this@SettingsActivity, oauthUrl)
                    }
                }
                SettingsScreen(
                    state = state,
                    onServerUrlChanged = viewModel::onServerUrlChanged,
                    onSuffixChanged = viewModel::onSuffixChanged,
                    onConnect = viewModel::connect,
                    onDisconnect = { viewModel.disconnect(MainActivity::class.java) },
                    onClose = { finish() }
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.onOauthUrlConsumed()
    }

    override fun onResume() {
        super.onResume()
        viewModel.onHostResumed()
    }
}
