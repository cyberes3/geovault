package com.geovault.tracker

import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.geovault.common.ui.auth.GeoVaultOAuthBrowserEffect
import com.geovault.common.auth.GeoVaultAuthExtras
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.common.ui.system.GeoVaultSystemBars
import com.geovault.common.ui.theme.GeoVaultTheme
import com.geovault.tracker.presentation.MainScreenViewModel
import com.geovault.tracker.presentation.SettingsViewModel
import com.geovault.tracker.presentation.TrackerAccountViewModel
import com.geovault.tracker.presentation.LiveTrackStreamingTargetCoordinator
import com.geovault.tracker.location.TrackingPermissionGate
import com.geovault.tracker.services.TrackingRuntimeStateStore
import com.geovault.tracker.ui.MainScreen

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OAUTH_ERROR = GeoVaultAuthExtras.OAUTH_ERROR_EXTRA_KEY
        const val EXTRA_OPEN_ALL_TRACKERS_ON_MAP =
            "com.geovault.tracker.EXTRA_OPEN_ALL_TRACKERS_ON_MAP"
        const val ACTION_DUMP_RECOVERY_TELEMETRY = "com.geovault.tracker.ACTION_DUMP_RECOVERY_TELEMETRY"
    }

    private val viewModel: MainScreenViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val accountViewModel: TrackerAccountViewModel by viewModels()
    private var streamingErrorReceiverRegistered = false
    private var trackingErrorReceiverRegistered = false
    private val trackingErrorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != TrackingService.ACTION_TRACKING_ERROR) return
            intent.getStringExtra(TrackingService.EXTRA_TRACKING_ERROR_MESSAGE)
                ?.takeIf { it.isNotBlank() }
                ?.let { viewModel.showExternalError(it) }
        }
    }
    private val streamingErrorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != LiveTrackStreamingService.ACTION_STREAMING_ERROR) return
            intent.getStringExtra(LiveTrackStreamingService.EXTRA_STREAMING_ERROR_MESSAGE)
                ?.takeIf { it.isNotBlank() }
                ?.let { viewModel.showExternalError(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntentAction(intent)
        consumeOpenAllTrackersMapIntentIfPresent(intent)
        GeoVaultSystemBars.applyAppChrome(activity = this)
        syncRuntimeSelectedTracker()
        accountViewModel.initialize()
        viewModel.initialize()
        settingsViewModel.initialize()

        setContent {
            GeoVaultTheme {
                val state by viewModel.state.collectAsState()
                val settingsState by settingsViewModel.state.collectAsState()
                val accountState by accountViewModel.state.collectAsState()
                val accountMainState = state.copy(
                    isAuthenticated = accountState.isLoggedIn,
                    serverUrl = accountState.serverUrl,
                    isConnecting = accountState.isConnecting,
                    oauthUrl = null,
                )
                LaunchedEffect(accountState.isLoggedIn, accountState.serverUrl, accountState.isConnecting) {
                    viewModel.onAccountStateChanged(accountState)
                }

                GeoVaultOAuthBrowserEffect(
                    oauthUrl = accountState.oauthUrl,
                    onConsumed = accountViewModel::onOauthUrlConsumed,
                )

                LaunchedEffect(Unit) {
                    intent.getStringExtra(EXTRA_OAUTH_ERROR)?.let { error ->
                        accountViewModel.showExternalError(error)
                        intent?.removeExtra(EXTRA_OAUTH_ERROR)
                    }
                }
                MainScreen(
                    mainScreenViewModel = viewModel,
                    state = accountMainState,
                    mapRecoveryRequestToken = state.mapRecoveryRequestToken,
                    onMapRecoveryRequestConsumed = viewModel::consumeMapRecoveryRequest,
                    onAuthServerUrlChanged = accountViewModel::onServerUrlChanged,
                    onAuthConnect = accountViewModel::connect,
                    onClearInfoMessage = viewModel::clearInfoMessage,
                    onClearUpdateAvailable = viewModel::clearUpdateAvailable,
                    onRequestStartTracking = viewModel::requestStartTracking,
                    onRequestStopTracking = viewModel::requestStopTracking,
                    onRequestManualPoint = viewModel::requestManualPoint,
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
        intent.getStringExtra(EXTRA_OAUTH_ERROR)?.let { error ->
            accountViewModel.showExternalError(error)
            intent.removeExtra(EXTRA_OAUTH_ERROR)
        }
    }

    override fun onResume() {
        super.onResume()
        accountViewModel.onHostResumed()
        viewModel.onHostResumed()
        settingsViewModel.onHostResumed()
    }

    override fun onStart() {
        super.onStart()
        syncRuntimeSelectedTracker()
        if (!trackingErrorReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                trackingErrorReceiver,
                IntentFilter(TrackingService.ACTION_TRACKING_ERROR),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            trackingErrorReceiverRegistered = true
        }
        if (!streamingErrorReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                streamingErrorReceiver,
                IntentFilter(LiveTrackStreamingService.ACTION_STREAMING_ERROR),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            streamingErrorReceiverRegistered = true
        }
        if (isTrackingServiceActiveOrStarting() &&
            !TrackingPermissionGate.hasLocationPermission(this)
        ) {
            TrackerAppServices.from(application).trackerSettingsRepository().clearWasTrackingBeforeExit()
            startService(
                Intent(this, TrackingService::class.java).apply {
                    action = TrackingService.ACTION_STOP
                }
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
            TrackingService.ACTION_STOP -> {
                TrackerAppServices.from(application).trackerSettingsRepository().clearWasTrackingBeforeExit()
                startService(Intent(this, TrackingService::class.java).apply {
                    this.action = action
                })
            }
            LiveTrackStreamingService.ACTION_STOP -> {
                LiveTrackStreamingTargetCoordinator.clearInMemoryRequests()
                startService(Intent(this, LiveTrackStreamingService::class.java).apply {
                    this.action = action
                })
                viewModel.requestMapRecoveryAfterStreamingStop()
            }
            ACTION_DUMP_RECOVERY_TELEMETRY -> {
                TrackingRecoveryCoordinator.dumpTelemetryToLogcat(
                    context = applicationContext,
                    reason = "intent_action"
                )
            }
        }
    }

    private fun syncRuntimeSelectedTracker() {
        SelectedTrackerManager.syncRuntimeSelectedTracker(this)
    }

    private fun isTrackingServiceActiveOrStarting(): Boolean {
        val runtime = TrackingRuntimeStateStore.state.value
        return runtime.sessionActive || runtime.startupActive
    }

    override fun onStop() {
        super.onStop()
        if (trackingErrorReceiverRegistered) {
            unregisterReceiver(trackingErrorReceiver)
            trackingErrorReceiverRegistered = false
        }
        if (streamingErrorReceiverRegistered) {
            unregisterReceiver(streamingErrorReceiver)
            streamingErrorReceiverRegistered = false
        }
        accountViewModel.onOauthUrlConsumed()
    }
}
