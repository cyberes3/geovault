package com.geovault.tracker

import android.app.Application
import android.content.Context
import android.content.Intent
import com.geovault.common.auth.GeoVaultAuthSession
import com.geovault.common.bootstrap.AppResetFlow
import com.geovault.common.bootstrap.GeoVaultAppBootstrap
import com.geovault.common.logging.GeoVaultAppVersionLog
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.common.maps.bootstrap.GeoVaultMapsBootstrap
import com.geovault.tracker.BuildConfig
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.logging.GeoVaultPointRecordingLog
import com.geovault.tracker.startup.WatchdogColdStartArmer
import com.geovault.tracker.streaming.ClearReason
import com.geovault.tracker.tracking.TrackingService
import com.geovault.tracker.tracking.TrackingServiceIntents

class TrackerApplication : Application(), GeoVaultAuthSession.AuthFailureListener {

    lateinit var bootstrap: GeoVaultAppBootstrap
        private set

    override fun onCreate() {
        super.onCreate()
        GeoVaultCaptureLog.init(this)
        GeoVaultPointRecordingLog.init(this)
        GeoVaultAppVersionLog.log(this, BuildConfig.GIT_COMMIT_SHA)
        bootstrap = GeoVaultAppBootstrap.builder(this)
            .auth(
                redirectUri = "${BuildConfig.APPLICATION_ID}://oauth/callback",
                clientId = GeoVaultAuthSession.OAUTH_CLIENT_ID_TRACKER,
                authFailureListener = this,
            ) { ctx -> TrackerAppServices.from(ctx.applicationContext as Application).initialAuthController() }
            .install(GeoVaultMapsBootstrap(TRACKER_MAIN_MAP_KEY))
            .resetHook(
                key = HOOK_STOP_SERVICES,
                phase = AppResetFlow.Phase.AFTER_TOKEN_CLEAR,
            ) { hookContext ->
                TrackingRecoveryCoordinator.markIntentionalStop(hookContext, reason = "app_reset")
                hookContext.startService(
                    Intent(hookContext, TrackingService::class.java).apply {
                        action = TrackingServiceIntents.ACTION_STOP
                    }
                )
                MapStreamingServiceHelper.stopStreaming(hookContext)
                TrackerAppServices.from(hookContext.applicationContext as Application)
                    .liveStreamSubscriptionRepository()
                    .clearAllLeases(ClearReason.LOGOUT)
            }
            .resetHook(
                key = HOOK_CLEAR_LOCAL,
                phase = AppResetFlow.Phase.AFTER_TOKEN_CLEAR,
            ) { hookContext ->
                SelectedTrackerManager.clearSelectedTrackerAndInvalidateCaches(hookContext)
            }
            .build()
        bootstrap.boot(this)

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
