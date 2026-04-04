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
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.common.ui.system.GeoVaultSystemBars
import com.geovault.common.ui.theme.GeoVaultTheme
import com.geovault.tracker.presentation.MainScreenViewModel
import com.geovault.tracker.location.TrackingPermissionGate
import com.geovault.tracker.services.TrackingRuntimeStateStore
import com.geovault.tracker.ui.MainScreen

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OAUTH_ERROR = "oauth_error"
        const val ACTION_DUMP_RECOVERY_TELEMETRY = "com.geovault.tracker.ACTION_DUMP_RECOVERY_TELEMETRY"
    }

    private val viewModel: MainScreenViewModel by viewModels()
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
        GeoVaultSystemBars.applyAppChrome(activity = this)
        syncRuntimeSelectedTracker()
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentAction(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.onHostResumed()
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
        val selectedTrackerId = SelectedTrackerPrefs.selectedTrackerId(this)
        val selectedTrackerName = SelectedTrackerPrefs.selectedTrackerName(this)
        TrackingRuntimeStateStore.update {
            it.copy(
                selectedTrackerId = selectedTrackerId,
                selectedTrackerName = selectedTrackerName
            )
        }
    }

    private fun isTrackingServiceActiveOrStarting(): Boolean {
        return TrackingRuntimeStateStore.state.value.isRunning || TrackingService.isStartupInProgress
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
    }

    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations &&
            com.geovault.tracker.services.LiveStreamRuntimeStateStore.state.value.isRunning
        ) {
            startService(
                Intent(this, LiveTrackStreamingService::class.java).apply {
                    action = LiveTrackStreamingService.ACTION_STOP
                }
            )
        }
        super.onDestroy()
    }
}
