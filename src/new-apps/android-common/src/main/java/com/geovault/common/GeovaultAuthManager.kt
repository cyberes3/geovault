package com.geovault.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object GeovaultAuthManager {
    private const val TAG = "GeovaultAuthManager"
    private const val PREFS_NAME = "geovault_prefs"
    private const val PREF_SERVER_URL = "server_url"
    private const val AUTH_PREFS_NAME = "geovault_auth_prefs"
    private const val PREF_ACCESS_TOKEN = "access_token"
    private const val PREF_REFRESH_TOKEN = "refresh_token"
    private const val PREF_EXPIRES_AT = "expires_at"
    private const val PREF_PKCE_VERIFIER = "pkce_code_verifier"
    private const val PREF_PKCE_STATE = "pkce_state"
    private const val PREF_USER_EMAIL = "cached_user_email"

    const val OAUTH_CLIENT_ID_UPLOADER = "geovault-android-uploader"
    const val OAUTH_CLIENT_ID_PLACES = "geovault-android-places"
    private const val OAUTH_SCOPE = "api"
    private const val TOKEN_ENDPOINT_PATH = "/api/oauth/token/"
    private const val AUTHORIZE_PATH = "/api/oauth/authorize/"
    private const val TOKEN_BUFFER_SECONDS = 60L
    private val authJson = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    @Volatile
    private var clientId: String = OAUTH_CLIENT_ID_UPLOADER

    @Volatile
    private var redirectUri: String? = null

    private var authPrefs: android.content.SharedPreferences? = null
    private val refreshLock = Any()
    private var authFailureListener: AuthFailureListener? = null
    private val resolveExecutor = Executors.newSingleThreadExecutor()

    interface AuthFailureListener {
        fun onAuthFailure(context: Context)
    }

    fun setAuthFailureListener(listener: AuthFailureListener?) {
        authFailureListener = listener
    }

    fun handleAuthFailure(context: Context) {
        authFailureListener?.onAuthFailure(context)
    }

    fun init(context: Context, redirectUri: String, clientId: String) {
        if (this.redirectUri != null) return
        this.redirectUri = redirectUri
        this.clientId = clientId
        val appContext = context.applicationContext
        authPrefs = appContext.getSharedPreferences(AUTH_PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun requireInitialized(): String {
        val uri = redirectUri
        require(!uri.isNullOrBlank()) {
            "GeovaultAuthManager not initialized. Call init(context, redirectUri, clientId) first."
        }
        return uri
    }

    private fun requirePrefs(): android.content.SharedPreferences {
        requireInitialized()
        return authPrefs
            ?: error("GeovaultAuthManager not initialized. Call init(context, redirectUri, clientId) first.")
    }

    private fun putSecureString(
        editor: android.content.SharedPreferences.Editor,
        key: String,
        value: String?
    ): android.content.SharedPreferences.Editor {
        if (value.isNullOrBlank()) {
            editor.remove(key)
        } else {
            editor.putString(key, SecureValueCipher.encrypt(value))
        }
        return editor
    }

    private fun getSecureString(
        prefs: android.content.SharedPreferences,
        key: String
    ): String? {
        val encrypted = prefs.getString(key, null) ?: return null
        val decrypted = SecureValueCipher.decrypt(encrypted)
        if (decrypted == null) {
            Log.w(TAG, "secure_decrypt_failed key=$key")
            prefs.edit().remove(key).apply()
        }
        return decrypted?.takeIf { it.isNotBlank() }
    }

    private fun plainPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getServerUrl(context: Context): String =
        plainPrefs(context).getString(PREF_SERVER_URL, "") ?: ""

    fun setServerUrl(context: Context, url: String, commit: Boolean = false) {
        val editor = plainPrefs(context).edit().putString(PREF_SERVER_URL, url)
        if (commit) editor.commit() else editor.apply()
    }

    fun normalizeServerUrl(url: String): String {
        var normalized = url.trim().trimStart('/').trimEnd('/')
        if (normalized.isNotEmpty() &&
            !normalized.startsWith("http://") &&
            !normalized.startsWith("https://")
        ) {
            normalized = "https://$normalized"
        }
        return normalized
    }

    fun resolveServerUrlToCanonical(url: String, callback: (Result<String>) -> Unit) {
        val base = url.trimEnd('/')
        if (base.startsWith("https://")) {
            callback(Result.success(base))
            return
        }
        if (!base.startsWith("http://")) {
            callback(Result.failure(IllegalArgumentException("Server URL must be http/https")))
            return
        }
        resolveExecutor.execute {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
            val request = Request.Builder().url("$base/").head().build()
            try {
                client.newCall(request).execute().use { response ->
                    val finalUrl = response.request.url
                    val defaultPort = if (finalUrl.scheme == "https") 443 else 80
                    val resolved = "${finalUrl.scheme}://${finalUrl.host}" +
                        if (finalUrl.port != defaultPort) ":${finalUrl.port}" else ""
                    callback(Result.success(resolved))
                }
            } catch (e: Exception) {
                callback(Result.failure(e))
            }
        }
    }

    fun isLoggedIn(context: Context): Boolean {
        val hasAccess = !getAccessToken(context).isNullOrBlank()
        val hasRefresh = !getRefreshToken().isNullOrBlank()
        val loggedIn = hasAccess || hasRefresh
        Log.i(TAG, "isLoggedIn hasAccess=$hasAccess hasRefresh=$hasRefresh loggedIn=$loggedIn")
        return loggedIn
    }

    fun getAuthDebugSnapshot(context: Context): String {
        return runCatching {
            val prefs = requirePrefs()
            val now = System.currentTimeMillis() / 1000
            val expiresAt = prefs.getLong(PREF_EXPIRES_AT, 0L)
            val hasAccess = !getRawAccessToken().isNullOrBlank()
            val hasRefresh = !getRefreshToken().isNullOrBlank()
            val loggedIn = hasAccess || hasRefresh
            "auth_snapshot hasAccess=$hasAccess hasRefresh=$hasRefresh loggedIn=$loggedIn now=$now expiresAt=$expiresAt"
        }.getOrElse { e ->
            "auth_snapshot unavailable reason=${e.javaClass.simpleName}"
        }
    }

    fun getAccessToken(context: Context): String? {
        val prefs = requirePrefs()
        val expiresAt = prefs.getLong(PREF_EXPIRES_AT, 0L)
        if (expiresAt > 0 && System.currentTimeMillis() / 1000 >= expiresAt - TOKEN_BUFFER_SECONDS) {
            return null
        }
        return getSecureString(prefs, PREF_ACCESS_TOKEN)
    }

    private fun getRawAccessToken(): String? =
        getSecureString(requirePrefs(), PREF_ACCESS_TOKEN)

    fun saveTokens(context: Context, accessToken: String, refreshToken: String?, expiresInSeconds: Long) {
        val editor = requirePrefs().edit()
        putSecureString(editor, PREF_ACCESS_TOKEN, accessToken)
        putSecureString(editor, PREF_REFRESH_TOKEN, refreshToken)
        val committed = editor
            .putLong(PREF_EXPIRES_AT, System.currentTimeMillis() / 1000 + expiresInSeconds)
            .commit()
        Log.i(TAG, "saveTokens committed=$committed expiresInSeconds=$expiresInSeconds refreshPresent=${!refreshToken.isNullOrBlank()}")
        Log.i(TAG, getAuthDebugSnapshot(context))
    }

    fun clearTokens(context: Context) {
        val committed = requirePrefs().edit()
            .remove(PREF_ACCESS_TOKEN)
            .remove(PREF_REFRESH_TOKEN)
            .remove(PREF_EXPIRES_AT)
            .remove(PREF_USER_EMAIL)
            .commit()
        Log.i(TAG, "clearTokens committed=$committed")
        Log.i(TAG, getAuthDebugSnapshot(context))
    }

    fun revokeToken(context: Context, token: String?) {
        if (token.isNullOrBlank()) return
        val serverUrl = getServerUrl(context)
        if (serverUrl.isBlank()) return

        val body = FormBody.Builder()
            .add("token", token)
            .add("client_id", clientId)
            .build()
        val request = Request.Builder()
            .url("${serverUrl.trimEnd('/')}/api/oauth/revoke_token/")
            .post(body)
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .build()

        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
            .newCall(request)
            .enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) = Unit
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    response.close()
                }
            })
    }

    fun revokeCurrentSession(context: Context) {
        // Revoke both tokens to mirror legacy sign-out semantics and reduce stale session risk.
        revokeToken(context, getAccessToken(context))
        revokeToken(context, getRefreshToken())
    }

    fun generatePkcePair(): Pair<String, String> {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val verifier = (1..64).map { chars.random() }.joinToString("")
        val bytes = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        val challenge = Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING)
            .replace('+', '-')
            .replace('/', '_')
            .replace("=", "")
        return verifier to challenge
    }

    fun generateOAuthStateNonce(length: Int = 16): String {
        val chars = "abcdef0123456789"
        return (1..length).map { chars.random() }.joinToString("")
    }

    fun savePkceState(context: Context, verifier: String, state: String) {
        val editor = requirePrefs().edit()
        putSecureString(editor, PREF_PKCE_VERIFIER, verifier)
        putSecureString(editor, PREF_PKCE_STATE, state)
        editor.commit()
    }

    fun getAndClearPkceState(context: Context): Pair<String, String>? {
        val prefs = requirePrefs()
        val verifier = getSecureString(prefs, PREF_PKCE_VERIFIER) ?: return null
        val state = getSecureString(prefs, PREF_PKCE_STATE) ?: return null
        prefs.edit().remove(PREF_PKCE_VERIFIER).remove(PREF_PKCE_STATE).apply()
        return verifier to state
    }

    fun buildAuthorizeUrl(serverUrl: String, codeChallenge: String, state: String): String {
        val uri = requireInitialized()
        val base = serverUrl.trimEnd('/')
        return "$base$AUTHORIZE_PATH?" +
            "response_type=code" +
            "&client_id=${Uri.encode(clientId)}" +
            "&redirect_uri=${Uri.encode(uri)}" +
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
        val uri = requireInitialized()
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", uri)
            .add("client_id", clientId)
            .add("code_verifier", codeVerifier)
            .build()
        val request = Request.Builder()
            .url("${serverUrl.trimEnd('/')}$TOKEN_ENDPOINT_PATH")
            .post(body)
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .build()
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        client.newCall(request).execute().use { response ->
            val payload = response.body.string()
            if (!response.isSuccessful) {
                val parsed = decodeAuthTokenPayload(payload)
                val message = parsed?.errorDescription
                    ?.takeIf { it.isNotBlank() }
                    ?: payload.ifBlank { "HTTP ${response.code}" }
                onError(message)
                return
            }
            val tokenPayload = decodeAuthTokenPayload(payload)
            val accessToken = tokenPayload?.accessToken?.trim().orEmpty()
            val refreshToken = tokenPayload?.refreshToken?.trim().orEmpty().takeIf { it.isNotBlank() }
            val expiresIn = tokenPayload?.expiresInSeconds ?: 43200L
            if (accessToken.isBlank()) {
                onError("No access_token in response")
                return
            }
            onSuccess(accessToken, refreshToken, expiresIn)
        }
    }

    fun getValidAccessToken(context: Context, forceRefreshForToken: String? = null): String? {
        val needsForceRefresh = forceRefreshForToken != null && getRawAccessToken() == forceRefreshForToken
        if (!needsForceRefresh) {
            val token = getAccessToken(context)
            if (!token.isNullOrBlank()) return token
        }

        synchronized(refreshLock) {
            val syncNeedsForceRefresh = forceRefreshForToken != null && getRawAccessToken() == forceRefreshForToken
            if (!syncNeedsForceRefresh) {
                val token = getAccessToken(context)
                if (!token.isNullOrBlank()) return token
            }

            val refreshToken = getRefreshToken() ?: return null
            val serverUrl = getServerUrl(context)
            if (serverUrl.isBlank()) return null

            val requestBody = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", clientId)
                .build()
            val request = Request.Builder()
                .url("${serverUrl.trimEnd('/')}$TOKEN_ENDPOINT_PATH")
                .post(requestBody)
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
                .build()
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()

            client.newCall(request).execute().use { response ->
                val payload = response.body.string()
                if (!response.isSuccessful) {
                    if (response.code in 400..499) return null
                    throw java.io.IOException("Server returned ${response.code}")
                }
                val tokenPayload = decodeAuthTokenPayload(payload)
                val accessToken = tokenPayload?.accessToken?.trim().orEmpty()
                val rotatedRefresh = tokenPayload?.refreshToken?.trim().orEmpty().takeIf { it.isNotBlank() }
                val expiresIn = tokenPayload?.expiresInSeconds ?: 43200L
                if (accessToken.isBlank()) throw java.io.IOException("Missing access_token in response")
                saveTokens(context, accessToken, rotatedRefresh ?: refreshToken, expiresIn)
                return accessToken
            }
        }
    }

    private fun getRefreshToken(): String? =
        getSecureString(requirePrefs(), PREF_REFRESH_TOKEN)

    fun getCachedUserEmail(context: Context): String? {
        return getSecureString(requirePrefs(), PREF_USER_EMAIL)
    }

    private fun setCachedUserEmail(context: Context, email: String?) {
        val editor = requirePrefs().edit()
        putSecureString(editor, PREF_USER_EMAIL, email)
        editor.apply()
    }

    fun fetchUserStatus(context: Context, callback: ((String?) -> Unit)? = null) {
        if (!isLoggedIn(context)) {
            setCachedUserEmail(context, null)
            callback?.invoke(null)
            return
        }
        val serverUrl = getServerUrl(context)
        if (serverUrl.isBlank()) {
            callback?.invoke(null)
            return
        }

        val client = RetrofitClient.getAuthenticatedOkHttpClient(context).newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url("${serverUrl.trimEnd('/')}/api/user/status/").build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                callback?.invoke(null)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val body = try {
                    response.body.string()
                } catch (_: Exception) {
                    ""
                }
                val statusCode = response.code
                response.close()
                if (statusCode == 200 && body.isNotEmpty()) {
                    val statusPayload = decodeUserStatusPayload(body)
                    val email = statusPayload?.email?.trim().orEmpty()
                    if (email.isNotEmpty()) {
                        setCachedUserEmail(context, email)
                        callback?.invoke(email)
                    } else {
                        callback?.invoke(null)
                    }
                } else if (statusCode == 401) {
                    clearTokens(context)
                    callback?.invoke(null)
                } else {
                    callback?.invoke(null)
                }
            }
        })
    }

    private fun decodeAuthTokenPayload(payload: String): AuthTokenPayload? {
        if (payload.isBlank()) return null
        return runCatching { authJson.decodeFromString<AuthTokenPayload>(payload) }.getOrNull()
    }

    private fun decodeUserStatusPayload(payload: String): UserStatusPayload? {
        if (payload.isBlank()) return null
        return runCatching { authJson.decodeFromString<UserStatusPayload>(payload) }.getOrNull()
    }

    fun launchOAuthInBrowser(context: Context, authorizeUrl: String) {
        val uri = Uri.parse(authorizeUrl)
        try {
            val customTabsIntent = CustomTabsIntent.Builder().build()
            customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            customTabsIntent.launchUrl(context, uri)
        } catch (_: Exception) {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    @Serializable
    private data class AuthTokenPayload(
        @SerialName("access_token")
        val accessToken: String? = null,
        @SerialName("refresh_token")
        val refreshToken: String? = null,
        @SerialName("expires_in")
        val expiresInSeconds: Long? = null,
        @SerialName("error_description")
        val errorDescription: String? = null
    )

    @Serializable
    private data class UserStatusPayload(
        val email: String? = null
    )
}
