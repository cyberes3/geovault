package com.geovault.tracker

import android.app.Application
import android.content.Context
import android.content.Intent
import com.geovault.common.AppResetFlow
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.logging.GeoVaultAppVersionLog
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.logging.GeoVaultPointRecordingLog
import com.geovault.common.maps.core.GeoVaultMainMapControllerStore
import com.geovault.common.maps.core.MapLibreInitializer
import com.geovault.tracker.BuildConfig
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.startup.WatchdogColdStartArmer
import com.geovault.tracker.streaming.ClearReason
import com.geovault.tracker.tracking.TrackingService
import com.geovault.tracker.tracking.TrackingServiceIntents

class TrackerApplication : Application(), GeovaultAuthManager.AuthFailureListener {

    override fun onCreate() {
        super.onCreate()
        GeoVaultCaptureLog.init(this)
        GeoVaultPointRecordingLog.init(this)
        GeoVaultAppVersionLog.log(this, BuildConfig.GIT_COMMIT_SHA)
        GeovaultAuthManager.init(
            context = this,
            redirectUri = "${BuildConfig.APPLICATION_ID}://oauth/callback",
            clientId = GeovaultAuthManager.OAUTH_CLIENT_ID_TRACKER,
        )
        GeovaultAuthManager.setAuthFailureListener(this)
        MapLibreInitializer.init(this)

        AppResetFlow.registerHook(
            key = HOOK_STOP_SERVICES,
            phase = AppResetFlow.Phase.AFTER_TOKEN_CLEAR,
        ) { hookContext ->
            TrackingRecoveryCoordinator.markIntentionalStop(hookContext, reason = "app_reset")
            hookContext.startService(
                Intent(hookContext, TrackingService::class.java).apply {
                    action = TrackingServiceIntents.ACTION_STOP
                }
            )
            // LOGOUT-HARDENING: a bare startService() here can throw IllegalStateException if
            // the reset flow runs while the app is backgrounded (Android's background-start
            // restriction), leaving the streaming service running with a signed-out token.
            // MapStreamingServiceHelper.stopStreaming already escalates to
            // startForegroundService on that failure; clearAllLeases additionally drops every
            // in-memory lease and forces a fresh stop dispatch so no owner can resurrect the
            // subscription after a stale reconcile tick races the logout.
            MapStreamingServiceHelper.stopStreaming(hookContext)
            TrackerAppServices.from(hookContext.applicationContext as Application)
                .liveStreamSubscriptionRepository()
                .clearAllLeases(ClearReason.LOGOUT)
        }

        AppResetFlow.registerHook(
            key = HOOK_CLEAR_LOCAL,
            phase = AppResetFlow.Phase.AFTER_TOKEN_CLEAR,
        ) { hookContext ->
            SelectedTrackerManager.clearSelectedTrackerAndInvalidateCaches(hookContext)
            GeoVaultMainMapControllerStore.releaseKey(TRACKER_MAIN_MAP_KEY)
        }

        TrackingNotificationChannels.ensureTrackingChannel(this)
        TrackingRecoveryCoordinator.createRecoveryChannel(this)
        WatchdogColdStartArmer(this).start()
    }

    override fun onAuthFailure(context: Context) {
        AppResetFlow.execute(
            context = context,
            reason = AppResetFlow.Reason.AUTH_FAILURE,
            mainActivityClass = MainActivity::class.java,
        )
    }

    companion object {
        const val TRACKER_MAIN_MAP_KEY = "tracker-main-map"
        private const val HOOK_STOP_SERVICES = "tracker_stop_services"
        private const val HOOK_CLEAR_LOCAL = "tracker_clear_local"
    }
}
