package com.geovault.uploader

import android.app.Application
import com.geovault.common.auth.GeoVaultAuthSession
import com.geovault.common.bootstrap.AppResetFlow
import com.geovault.common.bootstrap.GeoVaultAppBootstrap
import com.geovault.common.logging.GeoVaultAppVersionLog
import com.geovault.uploader.BuildConfig
import com.geovault.uploader.data.UploaderPreferences
import com.geovault.uploader.di.UploaderAppServices

class UploaderApplication : Application(), GeoVaultAuthSession.AuthFailureListener {
    lateinit var bootstrap: GeoVaultAppBootstrap
        private set

    override fun onCreate() {
        super.onCreate()
        GeoVaultAppVersionLog.log(this, BuildConfig.GIT_COMMIT_SHA)
        bootstrap = GeoVaultAppBootstrap.builder(this)
            .auth(
                redirectUri = "${BuildConfig.APPLICATION_ID}://oauth/callback",
                clientId = GeoVaultAuthSession.OAUTH_CLIENT_ID_UPLOADER,
                authFailureListener = this,
            ) { ctx -> UploaderAppServices.from(ctx.applicationContext as Application).initialAuthController() }
            .gate("uploader-prefs") { ctx ->
                UploaderPreferences.getInstance(ctx).preloadOnLaunch()
            }
            .resetHook(
                key = "uploader_clear_prefs",
                phase = AppResetFlow.Phase.AFTER_TOKEN_CLEAR,
            ) { context ->
                UploaderPreferences.getInstance(context).clearAll()
            }
            .build()
        bootstrap.boot(this)
    }

    override fun onAuthFailure(context: android.content.Context) {
        AppResetFlow.execute(
            context = context,
            reason = AppResetFlow.Reason.AUTH_FAILURE,
            mainActivityClass = MainActivity::class.java
        )
    }
}
