package com.geovault.places

import com.geovault.common.auth.GeoVaultOAuthCallbackActivityTemplate

class OAuthCallbackActivity : GeoVaultOAuthCallbackActivityTemplate() {
    override val mainActivityClass: Class<out android.app.Activity> = MainActivity::class.java
}
