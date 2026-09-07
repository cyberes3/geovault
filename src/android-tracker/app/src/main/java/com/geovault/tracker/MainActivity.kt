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
import com.geovault.common.ui.auth.GeoVaultAuthHost
import com.geovault.common.ui.auth.GeoVaultOAuthBrowserEffect
import com.geovault.common.auth.GeoVaultAuthExtras
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.common.ui.theme.GeoVaultTheme
import com.geovault.tracker.presentation.MainScreenViewModel
import com.geovault.tracker.presentation.SettingsViewModel
import com.geovault.common.auth.GeoVaultAccountViewModel
import com.geovault.tracker.location.TrackingPermissionGate
import com.geovault.tracker.services.TrackingRuntimeStateStore
import com.geovault.tracker.tracking.TrackingService
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
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val accountViewModel: GeoVaultAccountViewModel by viewModels {
        GeoVaultAccountViewModel.factory(TrackerAppServices.from(application).initialAuthController())
    }

    private var streamingErrorReceiverRegistered = false
    private var trackingErrorReceiverRegistered = false
    private val trackingErrorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != TrackingServiceIntents.ACTION_TRACKING_ERROR) return
            intent.getStringExtra(TrackingServiceIntents.EXTRA_TRACKING_ERROR_MESSAGE)
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
        if (!trackingErrorReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                trackingErrorReceiver,
                IntentFilter(TrackingServiceIntents.ACTION_TRACKING_ERROR),
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
            !TrackingPermissionGate.hasRequiredPermissionsForTracking(this)
        ) {
            TrackerAppServices.from(application).trackerSettingsRepository().clearWasTrackingBeforeExit()
            startService(
                Intent(this, TrackingService::class.java).apply {
                    action = TrackingServiceIntents.ACTION_STOP
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
            TrackingServiceIntents.ACTION_STOP -> {
                TrackerAppServices.from(application).trackerSettingsRepository().clearWasTrackingBeforeExit()
                startService(Intent(this, TrackingService::class.java).apply {
                    this.action = action
                })
            }
            LiveTrackStreamingService.ACTION_STOP -> {
                TrackerAppServices.from(application).liveStreamSubscriptionRepository().clearLeasesWithoutDispatch()
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
        GeoVaultAuthHost.onStop(accountViewModel)
    }
}
