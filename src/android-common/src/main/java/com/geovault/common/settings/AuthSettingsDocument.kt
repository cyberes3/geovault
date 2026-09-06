package com.geovault.common.settings

import kotlinx.serialization.Serializable

@Serializable
data class AuthSettingsDocument(
    val serverUrl: String = "",
    val accessToken: GeoVaultSecureString? = null,
    val refreshToken: GeoVaultSecureString? = null,
    val pkceVerifier: GeoVaultSecureString? = null,
    val pkceState: GeoVaultSecureString? = null,
    val lastConsumedPkceState: GeoVaultSecureString? = null,
    val cachedUserEmail: GeoVaultSecureString? = null,
    val expiresAt: Long = 0L,
    val lastConsumedPkceAt: Long = 0L,
) {
    companion object {
        const val SCHEMA_VERSION = 1
        const val FILE_NAME = "geovault_auth.settings"

        fun fromLegacy(blob: GeoVaultLegacySettingsBlob): AuthSettingsDocument {
            return AuthSettingsDocument(
                serverUrl = blob.stringValues["server_url"].orEmpty(),
                accessToken = GeoVaultSecureString.fromPersisted(blob.stringValues["access_token"]),
                refreshToken = GeoVaultSecureString.fromPersisted(blob.stringValues["refresh_token"]),
                pkceVerifier = GeoVaultSecureString.fromPersisted(blob.stringValues["pkce_code_verifier"]),
                pkceState = GeoVaultSecureString.fromPersisted(blob.stringValues["pkce_state"]),
                lastConsumedPkceState = GeoVaultSecureString.fromPersisted(
                    blob.stringValues["last_consumed_pkce_state"]
                ),
                cachedUserEmail = GeoVaultSecureString.fromPersisted(blob.stringValues["cached_user_email"]),
                expiresAt = blob.longValues["expires_at"] ?: 0L,
                lastConsumedPkceAt = blob.longValues["last_consumed_pkce_at"] ?: 0L,
            )
        }
    }
}
