package com.geovault.common.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.geovault.common.net.GeoVaultHttp
import com.geovault.common.net.GeoVaultServerUrl
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom

internal class GeoVaultOAuthClient(
    private val clientId: String,
    private val redirectUri: String,
) {
    private val secureRandom = SecureRandom()
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    fun generatePkcePair(): Pair<String, String> {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val verifier = randomString(chars, length = 64)
        val bytes = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        val challenge = Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING)
            .replace('+', '-')
            .replace('/', '_')
            .replace("=", "")
        return verifier to challenge
    }

    fun generateOAuthStateNonce(length: Int = 16): String {
        return randomString("abcdef0123456789", length)
    }

    fun buildAuthorizeUrl(serverUrl: GeoVaultServerUrl, codeChallenge: String, state: String): String {
        return serverUrl.resolve(AUTHORIZE_PATH) + "?" +
            "response_type=code" +
            "&client_id=${Uri.encode(clientId)}" +
            "&redirect_uri=${Uri.encode(redirectUri)}" +
            "&scope=${Uri.encode(OAUTH_SCOPE)}" +
            "&code_challenge=${Uri.encode(codeChallenge)}" +
            "&code_challenge_method=S256" +
            "&state=${Uri.encode(state)}"
    }

    fun exchangeCodeForTokens(
        serverUrl: GeoVaultServerUrl,
        code: String,
        codeVerifier: String,
    ): Result<GeoVaultOAuthTokens> {
        val tokenUrl = serverUrl.resolve(TOKEN_ENDPOINT_PATH)
        Log.i(TAG, "exchangeCodeForTokens: tokenUrl=$tokenUrl clientId=$clientId")
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("client_id", clientId)
            .add("code_verifier", codeVerifier)
            .build()
        val request = Request.Builder()
            .url(tokenUrl)
            .post(body)
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .build()
        return try {
            GeoVaultHttp.bootstrapClient().newCall(request).execute().use { response ->
                val payload = response.body.string()
                if (!response.isSuccessful) {
                    val parsed = decodeAuthTokenPayload(payload)
                    val message = parsed?.errorDescription?.takeIf { it.isNotBlank() }
                        ?: payload.ifBlank { "HTTP ${response.code}" }
                    return Result.failure(IllegalStateException(message))
                }
                val tokenPayload = decodeAuthTokenPayload(payload)
                val accessToken = tokenPayload?.accessToken?.trim().orEmpty()
                val refreshToken = tokenPayload?.refreshToken?.trim().orEmpty().takeIf { it.isNotBlank() }
                val expiresIn = tokenPayload?.expiresInSeconds ?: 43200L
                if (accessToken.isBlank()) {
                    return Result.failure(IllegalStateException("No access_token in response"))
                }
                Result.success(GeoVaultOAuthTokens(accessToken, refreshToken, expiresIn))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun revokeToken(serverUrl: GeoVaultServerUrl, token: String?) {
        if (token.isNullOrBlank()) return
        val body = FormBody.Builder()
            .add("token", token)
            .add("client_id", clientId)
            .build()
        val request = Request.Builder()
            .url(serverUrl.resolve("/api/oauth/revoke_token/"))
            .post(body)
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .build()
        GeoVaultHttp.bootstrapClient().newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) = Unit
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.close()
            }
        })
    }

    fun launchInBrowser(context: Context, authorizeUrl: String) {
        val uri = Uri.parse(authorizeUrl)
        val launchInNewTask = context !is Activity
        try {
            val customTabsIntent = CustomTabsIntent.Builder().build()
            if (launchInNewTask) {
                customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            customTabsIntent.launchUrl(context, uri)
        } catch (e: Exception) {
            Log.w(TAG, "CustomTabs failed, falling back to ACTION_VIEW — ${e.message}")
            val intent = Intent(Intent.ACTION_VIEW, uri)
            if (launchInNewTask) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    internal fun decodeAuthTokenPayload(payload: String): AuthTokenPayload? {
        if (payload.isBlank()) return null
        return runCatching { json.decodeFromString(AuthTokenPayload.serializer(), payload) }.getOrNull()
    }

    private fun randomString(chars: String, length: Int): String {
        return buildString(length.coerceAtLeast(0)) {
            repeat(length.coerceAtLeast(0)) {
                append(chars[secureRandom.nextInt(chars.length)])
            }
        }
    }

    companion object {
        private const val TAG = "GeoVaultOAuthClient"
        private const val OAUTH_SCOPE = "api"
        private const val TOKEN_ENDPOINT_PATH = "/api/oauth/token/"
        private const val AUTHORIZE_PATH = "/api/oauth/authorize/"
    }
}

data class GeoVaultOAuthTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresInSeconds: Long,
)

@Serializable
internal data class AuthTokenPayload(
    @SerialName("access_token")
    val accessToken: String? = null,
    @SerialName("refresh_token")
    val refreshToken: String? = null,
    @SerialName("expires_in")
    val expiresInSeconds: Long? = null,
    @SerialName("error_description")
    val errorDescription: String? = null,
)
