package com.geovault.uploader

import android.app.Application
import com.geovault.common.AppResetFlow
import com.geovault.common.GeovaultAuthManager
import com.geovault.uploader.data.UploaderPreferences

class UploaderApplication : Application(), GeovaultAuthManager.AuthFailureListener {
    override fun onCreate() {
        super.onCreate()
        GeovaultAuthManager.init(
            context = this,
            redirectUri = "com.geovault.uploader://oauth/callback",
            clientId = GeovaultAuthManager.OAUTH_CLIENT_ID_UPLOADER
        )
        AppResetFlow.registerHook(
            key = "uploader_clear_prefs",
            phase = AppResetFlow.Phase.AFTER_TOKEN_CLEAR
        ) { context ->
            UploaderPreferences.getInstance(context).clearAll()
        }
        GeovaultAuthManager.setAuthFailureListener(this)
    }

    override fun onAuthFailure(context: android.content.Context) {
        AppResetFlow.execute(
            context = context,
            reason = AppResetFlow.Reason.AUTH_FAILURE,
            mainActivityClass = MainActivity::class.java
        )
    }
}
