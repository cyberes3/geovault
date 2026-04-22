package com.geovault.common.auth

/**
 * Shared intent-extra keys used by the OAuth callback flow across GeoVault Android apps.
 *
 * Having a single source of truth prevents drift between [GeoVaultOAuthCallbackActivityTemplate]
 * and per-app `MainActivity`s, each of which must agree on the same string.
 */
object GeoVaultAuthExtras {
    /**
     * Intent extra key carrying an OAuth error message routed from the callback activity back
     * to the app's main activity for presentation.
     */
    const val OAUTH_ERROR_EXTRA_KEY: String = "oauth_error"
}
