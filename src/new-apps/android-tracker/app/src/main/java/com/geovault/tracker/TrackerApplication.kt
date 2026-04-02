package com.geovault.tracker

import android.app.Application
import android.content.Context
import com.geovault.common.AppResetFlow
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.maps.core.GeoVaultMainMapControllerStore
import com.geovault.common.maps.core.MapLibreInitializer

class TrackerApplication : Application(), GeovaultAuthManager.AuthFailureListener {

    override fun onCreate() {
        super.onCreate()
        GeovaultAuthManager.init(
            context = this,
            redirectUri = TRACKER_REDIRECT_URI,
            clientId = GeovaultAuthManager.OAUTH_CLIENT_ID_TRACKER,
        )
        GeovaultAuthManager.setAuthFailureListener(this)
        MapLibreInitializer.init(this)

        AppResetFlow.registerHook(
            key = HOOK_CLEAR_LOCAL,
            phase = AppResetFlow.Phase.AFTER_TOKEN_CLEAR,
        ) {
            GeoVaultMainMapControllerStore.forceReleaseKeyForReset(TRACKER_MAIN_MAP_KEY)
        }

        GeovaultAuthManager.fetchUserStatus(this)

        TrackingNotificationChannels.ensureTrackingChannel(this)
        TrackingRecoveryCoordinator.createRecoveryChannel(this)
    }

    override fun onAuthFailure(context: Context) {
        AppResetFlow.execute(
            context = context,
            reason = AppResetFlow.Reason.AUTH_FAILURE,
            mainActivityClass = MainActivity::class.java,
        )
    }

    companion object {
        const val TRACKER_REDIRECT_URI = "com.geovault.tracker://oauth/callback"
        const val TRACKER_MAIN_MAP_KEY = "tracker-main-map"
        private const val HOOK_CLEAR_LOCAL = "tracker_clear_local"
    }
}
