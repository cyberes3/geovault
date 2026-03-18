package com.geovault.uploader

import android.app.Application
import android.content.Context
import com.geovault.common.AppResetFlow
import com.geovault.common.GeovaultAuthManager

class UploaderApplication : Application(), GeovaultAuthManager.AuthFailureListener {
    companion object {
        private const val HOOK_CLEAR_UPLOADER_PREFS = "uploader_clear_prefs"
    }

    override fun onCreate() {
        super.onCreate()
        GeovaultAuthManager.init(this, "com.geovault.uploader://oauth/callback", GeovaultAuthManager.OAUTH_CLIENT_ID_UPLOADER)
        AppResetFlow.registerHook(
            key = HOOK_CLEAR_UPLOADER_PREFS,
            phase = AppResetFlow.Phase.AFTER_TOKEN_CLEAR
        ) { hookContext ->
            hookContext.getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        }
        GeovaultAuthManager.setAuthFailureListener(this)
        GeovaultAuthManager.fetchUserStatus(this)
    }

    override fun onAuthFailure(context: Context) {
        AppResetFlow.execute(
            context = context,
            reason = AppResetFlow.Reason.AUTH_FAILURE,
            mainActivityClass = MainActivity::class.java
        )
    }
}
