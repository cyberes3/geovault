package com.geovault.places

import android.app.Application
import android.content.Context
import com.geovault.common.AppResetFlow
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.maps.core.GeoVaultMainMapControllerStore
import com.geovault.places.di.PlacesAppServices
import com.geovault.common.maps.core.MapLibreInitializer

class PlacesApplication : Application(), GeovaultAuthManager.AuthFailureListener {
    companion object {
        private const val PLACES_REDIRECT_URI = "com.geovault.places://oauth/callback"
    }

    override fun onCreate() {
        super.onCreate()
        GeovaultAuthManager.init(
            context = this,
            redirectUri = PLACES_REDIRECT_URI,
            clientId = GeovaultAuthManager.OAUTH_CLIENT_ID_PLACES,
        )
        GeovaultAuthManager.setAuthFailureListener(this)
        MapLibreInitializer.init(this)
        AppResetFlow.registerHook(
            key = "places_clear_local",
            phase = AppResetFlow.Phase.AFTER_TOKEN_CLEAR,
        ) { hookContext ->
            GeoVaultMainMapControllerStore.forceReleaseKeyForReset(PLACES_MAIN_MAP_KEY)
            PlacesAppServices.from(this).cacheStore().clear()
            PlacesAppServices.from(this).navigationRepository().clearPending()
        }
        GeovaultAuthManager.fetchUserStatus(this)
    }

    override fun onAuthFailure(context: Context) {
        AppResetFlow.execute(
            context = context,
            reason = AppResetFlow.Reason.AUTH_FAILURE,
            mainActivityClass = MainActivity::class.java,
        )
    }
}
