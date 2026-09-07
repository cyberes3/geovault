package com.geovault.uploader

import android.widget.Toast
import com.geovault.common.auth.GeoVaultOAuthCallbackActivityTemplate

class OAuthCallbackActivity : GeoVaultOAuthCallbackActivityTemplate() {
    override val mainActivityClass: Class<out android.app.Activity> = MainActivity::class.java

    override fun onOAuthSuccess() {
        finishWithToast(getString(R.string.oauth_connected))
    }

    override fun onOAuthError(message: String) {
        finishWithToast(message)
    }

    private fun finishWithToast(message: String) {
        if (isDestroyed) return
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }
}
