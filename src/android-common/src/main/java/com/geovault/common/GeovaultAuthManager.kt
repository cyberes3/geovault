package com.geovault.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.geovault.common.auth.GeoVaultAuthStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Outcome of calling `/api/user/status/` (or the early bail-outs before a network request).
 */
data class FetchUserStatusResult(
    val email: String?,
    /**
     * True when the GeoVault API host responded in a way that distinguishes "server up" from
     * total outage: HTTP 200 with a non-empty body that decodes as JSON, or HTTP 401 (host
     * answered and rejected the session). Network failures, timeouts, empty bodies, and JSON
     * decode failures are false.
     */
    val isUserStatusEndpointReachable: Boolean,
)

object GeovaultAuthManager {
    private const val TAG = "GeovaultAuthManager"

    const val OAUTH_CLIENT_ID_UPLOADER = "geovault-android-uploader"
    const val OAUTH_CLIENT_ID_PLACES = "geovault-android-places"
    const val OAUTH_CLIENT_ID_TRACKER = "geovault-android-tracker"
    const val OAUTH_CLIENT_ID_NGS = "geovault-android-ngs"
    private const val OAUTH_SCOPE = "api"
    private const val TOKEN_ENDPOINT_PATH = "/api/oauth/token/"
    private const val AUTHORIZE_PATH = "/api/oauth/authorize/"
    private val authJson = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    @Volatile
    private var clientId: String = OAUTH_CLIENT_ID_UPLOADER

    @Volatile
    private var redirectUri: String? = null

    private val refreshLock = Any()
    private var authFailureListener: AuthFailureListener? = null
    private val resolveExecutor = Executors.newSingleThreadExecutor()
    private val secureRandom = SecureRandom()

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
        GeoVaultAuthStore.getInstance(context).preloadAll()
    }

    private fun requireInitialized(): String {
        val uri = redirectUri
        require(!uri.isNullOrBlank()) {
            "GeovaultAuthManager not initialized. Call init(context, redirectUri, clientId) first."
        }
        return uri
    }

    private fun store(context: Context): GeoVaultAuthStore =
        GeoVaultAuthStore.getInstance(context)

    fun getServerUrl(context: Context): String =
        store(context).getServerUrl()

    fun setServerUrl(context: Context, url: String, commit: Boolean = false) {
        store(context).setServerUrl(url)
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
        val s = store(context)
        val hasAccess = !s.getAccessToken().isNullOrBlank()
        val hasRefresh = !s.getRefreshToken().isNullOrBlank()
        val loggedIn = hasAccess || hasRefresh
        Log.d(TAG, "isLoggedIn hasAccess=$hasAccess hasRefresh=$hasRefresh loggedIn=$loggedIn")
        return loggedIn
    }

    fun getAuthDebugSnapshot(context: Context): String {
        return runCatching {
            val s = store(context)
            val now = System.currentTimeMillis() / 1000
            val expiresAt = s.getExpiresAt()
            val hasAccess = !s.getRawAccessToken().isNullOrBlank()
            val hasRefresh = !s.getRefreshToken().isNullOrBlank()
            val loggedIn = hasAccess || hasRefresh
            "auth_snapshot hasAccess=$hasAccess hasRefresh=$hasRefresh loggedIn=$loggedIn now=$now expiresAt=$expiresAt"
        }.getOrElse { e ->
            "auth_snapshot unavailable reason=${e.javaClass.simpleName}"
        }
    }

    fun getAccessToken(context: Context): String? =
        store(context).getAccessToken()

    private fun getRawAccessToken(context: Context): String? =
        store(context).getRawAccessToken()

    fun saveTokens(context: Context, accessToken: String, refreshToken: String?, expiresInSeconds: Long) {
        store(context).saveTokens(accessToken, refreshToken, expiresInSeconds)
        Log.i(TAG, "saveTokens expiresInSeconds=$expiresInSeconds refreshPresent=${!refreshToken.isNullOrBlank()}")
        Log.i(TAG, getAuthDebugSnapshot(context))
    }

    fun clearTokens(context: Context) {
        store(context).clearTokens()
        Log.i(TAG, "clearTokens")
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
        revokeToken(context, getAccessToken(context))
        revokeToken(context, store(context).getRefreshToken())
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
        val chars = "abcdef0123456789"
        return randomString(chars, length)
    }

    private fun randomString(chars: String, length: Int): String {
        return buildString(length.coerceAtLeast(0)) {
            repeat(length.coerceAtLeast(0)) {
                append(chars[secureRandom.nextInt(chars.length)])
            }
        }
    }

    fun savePkceState(context: Context, verifier: String, state: String) {
        Log.i(TAG, "savePkceState: state=$state verifierLen=${verifier.length}")
        store(context).savePkceState(verifier, state)
    }

    fun getAndClearPkceState(context: Context): Pair<String, String>? {
        val result = store(context).getAndClearPkceState()
        Log.i(TAG, "getAndClearPkceState: ${if (result != null) "found state=${result.second}" else "returned NULL"}")
        return result
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
        val tokenUrl = "${serverUrl.trimEnd('/')}$TOKEN_ENDPOINT_PATH"
        Log.i(TAG, "exchangeCodeForTokens: tokenUrl=$tokenUrl clientId=$clientId redirectUri=$uri codeLen=${code.length}")
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", uri)
            .add("client_id", clientId)
            .add("code_verifier", codeVerifier)
            .build()
        val request = Request.Builder()
            .url(tokenUrl)
            .post(body)
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .build()
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val payload = response.body.string()
                Log.d(TAG, "exchangeCodeForTokens: HTTP ${response.code} payloadLen=${payload.length}")
                if (!response.isSuccessful) {
                    val parsed = decodeAuthTokenPayload(payload)
                    val message = parsed?.errorDescription
                        ?.takeIf { it.isNotBlank() }
                        ?: payload.ifBlank { "HTTP ${response.code}" }
                    Log.e(TAG, "exchangeCodeForTokens: failed HTTP ${response.code} — $message")
                    onError(message)
                    return
                }
                val tokenPayload = decodeAuthTokenPayload(payload)
                val accessToken = tokenPayload?.accessToken?.trim().orEmpty()
                val refreshToken = tokenPayload?.refreshToken?.trim().orEmpty().takeIf { it.isNotBlank() }
                val expiresIn = tokenPayload?.expiresInSeconds ?: 43200L
                if (accessToken.isBlank()) {
                    Log.e(TAG, "exchangeCodeForTokens: no access_token in response body")
                    onError("No access_token in response")
                    return
                }
                Log.i(TAG, "exchangeCodeForTokens: success expiresIn=${expiresIn}s refreshPresent=${refreshToken != null}")
                onSuccess(accessToken, refreshToken, expiresIn)
            }
        } catch (e: Exception) {
            Log.e(TAG, "exchangeCodeForTokens: exception — ${e.javaClass.simpleName}: ${e.message}", e)
            onError(e.message ?: "Token exchange failed")
        }
    }

    fun getValidAccessToken(context: Context, forceRefreshForToken: String? = null): String? {
        val s = store(context)
        val needsForceRefresh = forceRefreshForToken != null && s.getRawAccessToken() == forceRefreshForToken
        if (!needsForceRefresh) {
            val token = s.getAccessToken()
            if (!token.isNullOrBlank()) return token
        }

        synchronized(refreshLock) {
            val syncNeedsForceRefresh = forceRefreshForToken != null && s.getRawAccessToken() == forceRefreshForToken
            if (!syncNeedsForceRefresh) {
                val token = s.getAccessToken()
                if (!token.isNullOrBlank()) return token
            }

            val refreshToken = s.getRefreshToken() ?: return null
            val serverUrl = s.getServerUrl()
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

    fun getCachedUserEmail(context: Context): String? {
        return store(context).getCachedUserEmail()
    }

    private fun setCachedUserEmail(context: Context, email: String?) {
        store(context).setCachedUserEmail(email)
    }

    fun fetchUserStatus(context: Context, callback: ((String?) -> Unit)? = null) {
        fetchUserStatusWithResult(context) { result ->
            callback?.invoke(result.email)
        }
    }

    fun fetchUserStatusWithResult(context: Context, callback: (FetchUserStatusResult) -> Unit) {
        if (!isLoggedIn(context)) {
            setCachedUserEmail(context, null)
            callback(FetchUserStatusResult(email = null, isUserStatusEndpointReachable = false))
            return
        }
        val serverUrl = getServerUrl(context)
        if (serverUrl.isBlank()) {
            callback(FetchUserStatusResult(email = null, isUserStatusEndpointReachable = false))
            return
        }

        val client = RetrofitClient.getAuthenticatedOkHttpClient(context).newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url("${serverUrl.trimEnd('/')}/api/user/status/").build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                callback(FetchUserStatusResult(email = null, isUserStatusEndpointReachable = false))
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
                    if (statusPayload != null) {
                        val email = statusPayload.email?.trim().orEmpty()
                        if (email.isNotEmpty()) {
                            setCachedUserEmail(context, email)
                            callback(
                                FetchUserStatusResult(
                                    email = email,
                                    isUserStatusEndpointReachable = true,
                                ),
                            )
                        } else {
                            callback(
                                FetchUserStatusResult(
                                    email = null,
                                    isUserStatusEndpointReachable = true,
                                ),
                            )
                        }
                    } else {
                        callback(FetchUserStatusResult(email = null, isUserStatusEndpointReachable = false))
                    }
                } else if (statusCode == 401) {
                    clearTokens(context)
                    callback(
                        FetchUserStatusResult(
                            email = null,
                            isUserStatusEndpointReachable = true,
                        ),
                    )
                } else {
                    callback(FetchUserStatusResult(email = null, isUserStatusEndpointReachable = false))
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
        Log.i(TAG, "launchOAuthInBrowser: host=${Uri.parse(authorizeUrl).host}")
        val uri = Uri.parse(authorizeUrl)
        try {
            val customTabsIntent = CustomTabsIntent.Builder().build()
            customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            customTabsIntent.launchUrl(context, uri)
        } catch (e: Exception) {
            Log.w(TAG, "launchOAuthInBrowser: CustomTabs failed, falling back to ACTION_VIEW — ${e.message}")
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
