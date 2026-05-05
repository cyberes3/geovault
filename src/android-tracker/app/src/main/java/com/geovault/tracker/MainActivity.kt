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
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.auth.GeoVaultAuthExtras
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.common.ui.system.GeoVaultSystemBars
import com.geovault.common.ui.theme.GeoVaultTheme
import com.geovault.tracker.presentation.MainScreenViewModel
import com.geovault.tracker.presentation.SettingsViewModel
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
        viewModel.initialize()
        settingsViewModel.initialize()

        setContent {
            GeoVaultTheme {
                val state by viewModel.state.collectAsState()
                val settingsState by settingsViewModel.state.collectAsState()

                LaunchedEffect(state.oauthUrl) {
                    state.oauthUrl?.let {
                        GeovaultAuthManager.launchOAuthInBrowser(this@MainActivity, it)
                    }
                }
                LaunchedEffect(settingsState.oauthUrl) {
                    settingsState.oauthUrl?.let {
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
                    mainScreenViewModel = viewModel,
                    state = state,
                    mapRecoveryRequestToken = state.mapRecoveryRequestToken,
                    onMapRecoveryRequestConsumed = viewModel::consumeMapRecoveryRequest,
                    onAuthServerUrlChanged = viewModel::onAuthServerUrlChanged,
                    onAuthConnect = viewModel::connectAuth,
                    onClearInfoMessage = viewModel::clearInfoMessage,
                    onClearUpdatePrompt = viewModel::clearUpdatePrompt,
                    onRequestStartTracking = viewModel::requestStartTracking,
                    onRequestStopTracking = viewModel::requestStopTracking,
                    onRequestManualPoint = viewModel::requestManualPoint,
                    settingsState = settingsState,
                    onSettingsServerUrlChanged = settingsViewModel::onServerUrlChanged,
                    onSettingsConnect = settingsViewModel::connect,
                    onSettingsDisconnect = { settingsViewModel.disconnect(MainActivity::class.java) },
                    onSettingsTrackingProfileSelected = settingsViewModel::setTrackingProfile,
                    onSettingsLoggingIntervalInput = settingsViewModel::setLoggingIntervalSecFromInput,
                    onSettingsDistanceFilterInput = settingsViewModel::setDistanceFilterMetersFromInput,
                    onSettingsAccuracyFilterInput = settingsViewModel::setAccuracyFilterMetersFromInput,
                    onSettingsLowAccuracyFallbackEnabled = settingsViewModel::setLowAccuracyFallbackEnabled,
                    onSettingsLowAccuracyTimeoutInput = settingsViewModel::setLowAccuracyFallbackTimeoutSecFromInput,
                    onSettingsStartOnBoot = settingsViewModel::setStartOnBoot,
                    onSettingsStartOnLaunch = settingsViewModel::setStartTrackingOnLaunch,
                    onSettingsSendExtendedData = settingsViewModel::setSendExtendedData,
                    onSettingsSignificantMotionOnly = settingsViewModel::setSignificantDataOnly,
                    onSettingsAutoTrackingMode = settingsViewModel::setAutoTrackingMode,
                    onSettingsKeepScreenOnMap = settingsViewModel::setKeepScreenOnWhileViewingMap,
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
            viewModel.showExternalError(error)
            intent.removeExtra(EXTRA_OAUTH_ERROR)
        }
    }

    override fun onResume() {
        super.onResume()
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
        viewModel.onOauthUrlConsumed()
        settingsViewModel.onOauthUrlConsumed()
    }
}
