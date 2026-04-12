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
import com.geovault.tracker.presentation.SettingsViewModel
import com.geovault.tracker.ui.SettingsScreen

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
                    state.oauthUrl?.let {
                        GeovaultAuthManager.launchOAuthInBrowser(this@SettingsActivity, it)
                    }
                }
                SettingsScreen(
                    state = state,
                    onServerUrlChanged = viewModel::onServerUrlChanged,
                    onConnect = viewModel::connect,
                    onDisconnect = { viewModel.disconnect(MainActivity::class.java) },
                    onTrackingProfileSelected = viewModel::setTrackingProfile,
                    onLoggingIntervalInput = viewModel::setLoggingIntervalSecFromInput,
                    onDistanceFilterInput = viewModel::setDistanceFilterMetersFromInput,
                    onAccuracyFilterInput = viewModel::setAccuracyFilterMetersFromInput,
                    onLowAccuracyFallbackEnabled = viewModel::setLowAccuracyFallbackEnabled,
                    onLowAccuracyTimeoutInput = viewModel::setLowAccuracyFallbackTimeoutSecFromInput,
                    onStartOnBoot = viewModel::setStartOnBoot,
                    onStartOnLaunch = viewModel::setStartTrackingOnLaunch,
                    onSendExtendedData = viewModel::setSendExtendedData,
                    onSignificantMotionOnly = viewModel::setSignificantDataOnly,
                    onAutoTrackingMode = viewModel::setAutoTrackingMode,
                    onKeepScreenOnMap = viewModel::setKeepScreenOnWhileViewingMap,
                    onRefreshHiddenTrackerItems = viewModel::refreshHiddenTrackerItems,
                    onUnhideTrackerItem = viewModel::unhideTrackerItem,
                    onUnhideAllTrackerItems = viewModel::unhideAllTrackerItems,
                    onOpenAllTrackersOnMap = {
                        startActivity(
                            Intent(this@SettingsActivity, MainActivity::class.java).apply {
                                flags =
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                putExtra(MainActivity.EXTRA_OPEN_ALL_TRACKERS_ON_MAP, true)
                            },
                        )
                    },
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
