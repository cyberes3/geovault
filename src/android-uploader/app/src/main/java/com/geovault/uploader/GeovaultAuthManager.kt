package com.geovault.uploader

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Manages OAuth2 (Authorization Code + PKCE) and token storage for GeoVault API.
 * Server URL is stored in plain prefs; tokens in EncryptedSharedPreferences.
 */
object GeovaultAuthManager {

    private const val PREFS_NAME = "geovault_prefs"
    private const val PREF_SERVER_URL = "server_url"
    private const val AUTH_PREFS_NAME = "geovault_auth_prefs"
    private const val PREF_ACCESS_TOKEN = "access_token"
    private const val PREF_REFRESH_TOKEN = "refresh_token"
    private const val PREF_EXPIRES_AT = "expires_at"
    private const val PREF_PKCE_VERIFIER = "pkce_code_verifier"
    private const val PREF_PKCE_STATE = "pkce_state"

    const val OAUTH_CLIENT_ID = "geovault-android"
    const val OAUTH_REDIRECT_URI = "com.geovault.uploader://oauth/callback"
    const val OAUTH_SCOPE = "api"
    private const val TOKEN_ENDPOINT_PATH = "/api/oauth/token/"
    private const val AUTHORIZE_PATH = "/api/oauth/authorize/"
    private const val TOKEN_BUFFER_SECONDS = 60L

    private var encryptedPrefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (encryptedPrefs != null) return
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        encryptedPrefs = EncryptedSharedPreferences.create(
            context,
            AUTH_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun requirePrefs(context: Context): android.content.SharedPreferences {
        init(context)
        return encryptedPrefs!!
    }

    private fun plainPrefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getServerUrl(context: Context): String =
        plainPrefs(context).getString(PREF_SERVER_URL, "") ?: ""

    fun setServerUrl(context: Context, url: String, commit: Boolean = false) {
        val editor = plainPrefs(context).edit().putString(PREF_SERVER_URL, url)
        if (commit) editor.commit() else editor.apply()
    }

    /**
     * True if the user has a valid session: either a non-expired access token or a refresh token
     * (so we can obtain a new access token). After access token expiry (~12h), we still consider
     * the user logged in if a refresh token exists.
     */
    fun isLoggedIn(context: Context): Boolean {
        if (!getAccessToken(context).isNullOrBlank()) return true
        return !getRefreshToken(context).isNullOrBlank()
    }

    fun getAccessToken(context: Context): String? {
        val prefs = requirePrefs(context)
        val expiresAt = prefs.getLong(PREF_EXPIRES_AT, 0L)
        if (expiresAt > 0 && System.currentTimeMillis() / 1000 >= expiresAt - TOKEN_BUFFER_SECONDS) {
            return null
        }
        return prefs.getString(PREF_ACCESS_TOKEN, null)?.takeIf { it.isNotBlank() }
    }

    /**
     * Persist tokens. Uses commit() so the write is durable before returning; this avoids a race
     * where the OAuth callback finishes and the next activity reads prefs before apply() has flushed,
     * which could yield empty tokens and 401 on the first request after sign-in.
     */
    fun saveTokens(context: Context, accessToken: String, refreshToken: String?, expiresInSeconds: Long) {
        requirePrefs(context).edit()
            .putString(PREF_ACCESS_TOKEN, accessToken)
            .putString(PREF_REFRESH_TOKEN, refreshToken ?: "")
            .putLong(PREF_EXPIRES_AT, System.currentTimeMillis() / 1000 + expiresInSeconds)
            .commit()
    }

    fun clearTokens(context: Context) {
        requirePrefs(context).edit()
            .remove(PREF_ACCESS_TOKEN)
            .remove(PREF_REFRESH_TOKEN)
            .remove(PREF_EXPIRES_AT)
            .apply()
    }

    fun generatePkcePair(): Pair<String, String> {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val verifier = (1..64).map { chars.random() }.joinToString("")
        val bytes = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        val challenge = Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING)
            .replace('+', '-').replace('/', '_').replace("=", "")
        return verifier to challenge
    }

    /**
     * Save PKCE verifier and state before launching the browser. Uses commit() so they are
     * durable if the process is killed while in the browser; otherwise the callback may read
     * empty state and show "Invalid state".
     */
    fun savePkceState(context: Context, verifier: String, state: String) {
        requirePrefs(context).edit()
            .putString(PREF_PKCE_VERIFIER, verifier)
            .putString(PREF_PKCE_STATE, state)
            .commit()
    }

    fun getAndClearPkceState(context: Context): Pair<String, String>? {
        val prefs = requirePrefs(context)
        val verifier = prefs.getString(PREF_PKCE_VERIFIER, null) ?: return null
        val state = prefs.getString(PREF_PKCE_STATE, null) ?: return null
        prefs.edit().remove(PREF_PKCE_VERIFIER).remove(PREF_PKCE_STATE).apply()
        return verifier to state
    }

    fun buildAuthorizeUrl(serverUrl: String, codeChallenge: String, state: String): String {
        val base = serverUrl.trimEnd('/')
        return "$base$AUTHORIZE_PATH?" +
            "response_type=code" +
            "&client_id=${Uri.encode(OAUTH_CLIENT_ID)}" +
            "&redirect_uri=${Uri.encode(OAUTH_REDIRECT_URI)}" +
            "&scope=${Uri.encode(OAUTH_SCOPE)}" +
            "&code_challenge=${Uri.encode(codeChallenge)}" +
            "&code_challenge_method=S256" +
            "&state=${Uri.encode(state)}"
    }

    fun exchangeCodeForTokens(
        serverUrl: String,
        code: String,
        codeVerifier: String,
        onSuccess: (accessToken: String, refreshToken: String?, expiresIn: Long) -> Unit,
        onError: (String) -> Unit
    ) {
        val url = serverUrl.trimEnd('/') + TOKEN_ENDPOINT_PATH
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", OAUTH_REDIRECT_URI)
            .add("client_id", OAUTH_CLIENT_ID)
            .add("code_verifier", codeVerifier)
            .build()
        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .build()
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                val msg = try {
                    JSONObject(responseBody).optString("error_description", responseBody)
                } catch (_: Exception) {
                    responseBody.ifEmpty { "HTTP ${response.code}" }
                }
                onError(msg)
                return
            }
            val json = JSONObject(responseBody)
            val accessToken = json.optString("access_token")
            val refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() }
            val expiresIn = json.optLong("expires_in", 43200L)
            if (accessToken.isBlank()) {
                onError("No access_token in response")
                return
            }
            onSuccess(accessToken, refreshToken, expiresIn)
        }
    }

    /**
     * Exchange refresh_token for a new access token. If the server uses refresh token rotation,
     * it returns a new refresh_token in the response; that value is passed to onSuccess and must
     * be saved so the next refresh uses it (the old token is revoked).
     */
    fun refreshAccessToken(
        serverUrl: String,
        refreshToken: String,
        onSuccess: (accessToken: String, newRefreshToken: String?, expiresIn: Long) -> Unit,
        onError: () -> Unit
    ): Boolean {
        val url = serverUrl.trimEnd('/') + TOKEN_ENDPOINT_PATH
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", OAUTH_CLIENT_ID)
            .build()
        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .build()
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            onError()
            return false
        }
        val json = JSONObject(responseBody)
        val accessToken = json.optString("access_token")
        val newRefreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() }
        val expiresIn = json.optLong("expires_in", 43200L)
        if (accessToken.isBlank()) {
            onError()
            return false
        }
        onSuccess(accessToken, newRefreshToken, expiresIn)
        return true
    }

    fun getRefreshToken(context: Context): String? =
        requirePrefs(context).getString(PREF_REFRESH_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun getValidAccessToken(context: Context): String? {
        var token = getAccessToken(context)
        if (!token.isNullOrBlank()) return token
        val refreshToken = getRefreshToken(context) ?: return null
        val serverUrl = getServerUrl(context)
        if (serverUrl.isBlank()) return null
        var newAccess: String? = null
        var newRefresh: String? = null
        var newExpires: Long = 0L
        refreshAccessToken(serverUrl, refreshToken,
            onSuccess = { access, newRt, expires ->
                newAccess = access
                newRefresh = newRt
                newExpires = expires
            },
            onError = { }
        )
        if (newAccess != null && newExpires > 0) {
            saveTokens(context, newAccess!!, newRefresh ?: refreshToken, newExpires)
            return newAccess
        }
        return null
    }

    fun launchOAuthInBrowser(context: Context, authorizeUrl: String) {
        val uri = Uri.parse(authorizeUrl)
        try {
            val customTabsIntent = CustomTabsIntent.Builder().build()
            customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            customTabsIntent.launchUrl(context, uri)
        } catch (e: Exception) {
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
