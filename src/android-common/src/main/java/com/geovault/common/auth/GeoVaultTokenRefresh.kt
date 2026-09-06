package com.geovault.common.auth

import com.geovault.common.net.GeoVaultHttp
import com.geovault.common.net.GeoVaultServerUrl
import okhttp3.FormBody
import okhttp3.Request
import java.io.IOException

internal class GeoVaultTokenRefresh(
    private val store: GeoVaultAuthStore,
    private val clientId: String,
    private val oauthClient: GeoVaultOAuthClient,
) {
    private val refreshLock = Any()

    /**
     * Blocking refresh for the OkHttp authenticator. Callers on the interceptor path
     * must use [GeoVaultAuthStore.getAccessToken] only.
     */
    fun refresh(forceRefreshForToken: String?): String? {
        synchronized(refreshLock) {
            val needsForceRefresh = forceRefreshForToken != null &&
                store.getRawAccessToken() == forceRefreshForToken
            if (!needsForceRefresh) {
                val token = store.getAccessToken()
                if (!token.isNullOrBlank()) return token
            }
            val refreshToken = store.getRefreshToken() ?: return null
            val serverUrl = GeoVaultServerUrl.parse(store.getServerUrl()) ?: return null
            val requestBody = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", clientId)
                .build()
            val request = Request.Builder()
                .url(serverUrl.resolve("/api/oauth/token/"))
                .post(requestBody)
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
                .build()
            GeoVaultHttp.bootstrapClient().newCall(request).execute().use { response ->
                val payload = response.body.string()
                if (!response.isSuccessful) {
                    if (response.code in 400..499) return null
                    throw IOException("Server returned ${response.code}")
                }
                val tokenPayload = oauthClient.decodeAuthTokenPayload(payload)
                val accessToken = tokenPayload?.accessToken?.trim().orEmpty()
                val rotatedRefresh = tokenPayload?.refreshToken?.trim().orEmpty().takeIf { it.isNotBlank() }
                val expiresIn = tokenPayload?.expiresInSeconds ?: 43200L
                if (accessToken.isBlank()) throw IOException("Missing access_token in response")
                store.saveTokens(accessToken, rotatedRefresh ?: refreshToken, expiresIn)
                return accessToken
            }
        }
    }
}
