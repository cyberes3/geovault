package com.geovault.uploader

import android.app.Application
import android.content.Context
import com.geovault.common.auth.GeoVaultAuthSession
import com.geovault.common.bootstrap.AppResetFlow
import com.geovault.common.bootstrap.GeoVaultAppBootstrap
import com.geovault.common.logging.GeoVaultAppVersionLog
import com.geovault.uploader.BuildConfig
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
            .gate("uploader-settings") { ctx ->
                UploaderAppServices.from(ctx.applicationContext as Application).settingsStore().preloadOnLaunch()
            }
            .resetHook(
                key = "uploader_cancel_upload",
                phase = AppResetFlow.Phase.BEFORE_TOKEN_CLEAR,
            ) { context ->
                val services = UploaderAppServices.from(context.applicationContext as Application)
                services.uploadRepository().cancelAllUploads()
            }
            .resetHook(
                key = "uploader_clear_prefs",
                phase = AppResetFlow.Phase.AFTER_TOKEN_CLEAR,
            ) { context ->
                UploaderAppServices.from(context.applicationContext as Application).settingsStore().clearAll()
            }
            .build()
        bootstrap.boot(this)
    }

    override fun onAuthFailure(context: Context) {
        AppResetFlow.execute(
            context = context,
            reason = AppResetFlow.Reason.AUTH_FAILURE,
            mainActivityClass = MainActivity::class.java,
        )
    }
}
