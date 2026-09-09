package com.geovault.tracker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.geovault.common.ui.auth.GeoVaultAuthHost
import com.geovault.common.ui.auth.GeoVaultOAuthBrowserEffect
import com.geovault.common.auth.GeoVaultAuthExtras
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.common.ui.theme.GeoVaultTheme
import com.geovault.tracker.presentation.HomeViewModel
import com.geovault.tracker.presentation.MainScreenViewModel
import com.geovault.tracker.presentation.SettingsViewModel
import com.geovault.common.auth.GeoVaultAccountViewModel
import com.geovault.tracker.location.TrackingPermissionGate
import com.geovault.tracker.runtime.RecoveryTelemetry
import com.geovault.tracker.runtime.TrackerRuntimeCommands
import com.geovault.tracker.runtime.TrackerRuntimeEngine
import com.geovault.tracker.runtime.TrackerRuntimeStore
import com.geovault.tracker.tracking.TrackingServiceIntents
import com.geovault.tracker.ui.MainScreen

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OAUTH_ERROR = GeoVaultAuthExtras.OAUTH_ERROR_EXTRA_KEY
        const val EXTRA_OPEN_ALL_TRACKERS_ON_MAP =
            "com.geovault.tracker.EXTRA_OPEN_ALL_TRACKERS_ON_MAP"
        const val ACTION_DUMP_RECOVERY_TELEMETRY = "com.geovault.tracker.ACTION_DUMP_RECOVERY_TELEMETRY"
    }

    private val viewModel: MainScreenViewModel by viewModels()
    private val homeViewModel: HomeViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val accountViewModel: GeoVaultAccountViewModel by viewModels {
        GeoVaultAccountViewModel.factory(TrackerAppServices.from(application).initialAuthController())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        GeoVaultAuthHost.installSplash(
            this,
            (application as TrackerApplication).bootstrap.isReady,
        )
        super.onCreate(savedInstanceState)
        handleIntentAction(intent)
        consumeOpenAllTrackersMapIntentIfPresent(intent)
        GeoVaultAuthHost.onCreate(this, accountViewModel)
        syncRuntimeSelectedTracker()
        viewModel.initialize()
        settingsViewModel.initialize()

        setContent {
            GeoVaultTheme {
                val state by viewModel.state.collectAsState()
                val settingsState by settingsViewModel.state.collectAsState()
                val accountState by accountViewModel.state.collectAsState()
                LaunchedEffect(accountState.isLoggedIn) {
                    viewModel.onAccountStateChanged(accountState)
                    homeViewModel.onAccountStateChanged(accountState.isLoggedIn)
                }

                GeoVaultOAuthBrowserEffect(
                    oauthUrl = accountState.oauthUrl,
                    onConsumed = accountViewModel::onOauthUrlConsumed,
                )
                MainScreen(
                    mainScreenViewModel = viewModel,
                    state = state,
                    mapRecoveryRequestToken = state.mapRecoveryRequestToken,
                    onMapRecoveryRequestConsumed = viewModel::consumeMapRecoveryRequest,
                    onAuthServerUrlChanged = accountViewModel::onServerUrlChanged,
                    onAuthConnect = accountViewModel::connect,
                    onClearUpdateAvailable = viewModel::clearUpdateAvailable,
                    settingsState = settingsState,
                    accountState = accountState,
                    onSettingsServerUrlChanged = accountViewModel::onServerUrlChanged,
                    onSettingsConnect = accountViewModel::connect,
                    onSettingsDisconnect = { accountViewModel.disconnect(MainActivity::class.java) },
                    onSettingsLowAccuracyFallbackEnabled = settingsViewModel::setLowAccuracyFallbackEnabled,
                    onSettingsLowAccuracyTimeoutInput = settingsViewModel::setLowAccuracyFallbackTimeoutSecFromInput,
                    onSettingsStartOnBoot = settingsViewModel::setStartOnBoot,
                    onSettingsStartOnLaunch = settingsViewModel::setStartTrackingOnLaunch,
                    onSettingsSendExtendedData = settingsViewModel::setSendExtendedData,
                    onSettingsSignificantMotionOnly = settingsViewModel::setSignificantDataOnly,
                    onSettingsSparseTracking = settingsViewModel::setSparseTracking,
                    onSettingsKeepScreenOnMap = settingsViewModel::setKeepScreenOnWhileViewingMap,
                    onSettingsGroupModeFitOnlyActiveTrackers = settingsViewModel::setGroupModeFitOnlyActiveTrackers,
                    onSettingsRefreshHiddenTrackerItems = settingsViewModel::refreshHiddenTrackerItems,
                    onSettingsUnhideTrackerItem = settingsViewModel::unhideTrackerItem,
                    onSettingsUnhideAllTrackerItems = settingsViewModel::unhideAllTrackerItems,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentAction(intent)
        consumeOpenAllTrackersMapIntentIfPresent(intent)
        GeoVaultAuthHost.onNewIntent(intent, accountViewModel)
    }

    override fun onResume() {
        super.onResume()
        GeoVaultAuthHost.onResume(accountViewModel)
        viewModel.onHostResumed()
        settingsViewModel.onHostResumed()
    }

    override fun onStart() {
        super.onStart()
        syncRuntimeSelectedTracker()
        if (isTrackingServiceActiveOrStarting() &&
            !TrackingPermissionGate.hasRequiredPermissionsForTracking(this)
        ) {
            TrackerRuntimeEngine.get(this).handle(
                TrackerRuntimeCommands.Stop(reason = "permission_revoked"),
            )
            viewModel.showExternalError(getString(R.string.location_permission_revoked))
        }
    }

    private fun consumeOpenAllTrackersMapIntentIfPresent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_ALL_TRACKERS_ON_MAP, false) != true) return
        intent.removeExtra(EXTRA_OPEN_ALL_TRACKERS_ON_MAP)
        viewModel.requestOpenAllTrackersOnMapFromIntent()
    }

    private fun handleIntentAction(intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            TrackingServiceIntents.ACTION_STOP -> {
                TrackerRuntimeEngine.get(this).handle(
                    TrackerRuntimeCommands.Stop(reason = "activity_stop_intent"),
                )
            }
            LiveTrackStreamingService.ACTION_STOP -> {
                TrackerAppServices.from(application)
                    .liveStreamSubscriptionRepository()
                    .clearAllLeases(com.geovault.tracker.streaming.ClearReason.NOTIFICATION)
                viewModel.requestMapRecoveryAfterStreamingStop()
            }
            ACTION_DUMP_RECOVERY_TELEMETRY -> {
                RecoveryTelemetry.dumpToLogcat(
                    context = applicationContext,
                    reason = "intent_action"
                )
            }
        }
    }

    private fun syncRuntimeSelectedTracker() {
        TrackerAppServices.from(application).catalogSelectionController().seedFromPersist(this)
    }

    private fun isTrackingServiceActiveOrStarting(): Boolean {
        return TrackerRuntimeStore.value.isRecording
    }

    override fun onStop() {
        super.onStop()
        GeoVaultAuthHost.onStop(accountViewModel)
    }
}
