package com.geovault.uploader

import android.app.Application
import com.geovault.common.GeovaultAuthManager

class UploaderApplication : Application(), GeovaultAuthManager.AuthFailureListener {
    override fun onCreate() {
        super.onCreate()
        GeovaultAuthManager.init(this, "com.geovault.uploader://oauth/callback", GeovaultAuthManager.OAUTH_CLIENT_ID_UPLOADER)
        GeovaultAuthManager.setAuthFailureListener(this)
        GeovaultAuthManager.fetchUserStatus(this)
    }

    override fun onAuthFailure(context: Context) {
        // Clear tokens
        GeovaultAuthManager.clearTokens(context)
        
        // Clear app-specific prefs (matches AppResetHelper logic)
        context.getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        
        // Return to login/settings screen
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        context.startActivity(intent)
    }
}
