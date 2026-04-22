package com.geovault.uploader

import android.widget.Toast
import com.geovault.common.auth.GeoVaultOAuthCallbackActivityTemplate

class OAuthCallbackActivity : GeoVaultOAuthCallbackActivityTemplate() {
    override val mainActivityClass: Class<out android.app.Activity> = MainActivity::class.java

    /**
     * Uploader keeps the caller's task on the stack so users return to the share sheet / host
     * after authenticating. Show a toast and finish rather than navigating to MainActivity.
     */
    override fun onOAuthSuccess() {
        if (isDestroyed) return
        Toast.makeText(this, "Connected successfully", Toast.LENGTH_SHORT).show()
        finish()
    }
}
