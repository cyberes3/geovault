package com.geovault.common.auth

import android.content.Context
import android.util.Log
import com.geovault.common.bootstrap.AppResetFlow
import com.geovault.common.net.GeoVaultConnectivity
import com.geovault.common.net.GeoVaultHttp
import com.geovault.common.net.GeoVaultServerUrl

/**
 * App-scoped auth/session owner. Construct once via [create] from Application / AppServices.
 *
 * Maps, Retrofit, ContentProviders, and OAuth callbacks resolve the same instance through [get].
 */
class GeoVaultAuthSession private constructor(
    private val appContext: Context,
    private val oauthConfig: OAuthConfig,
    private val store: GeoVaultAuthStore,
) : ServerConfigService, OAuthPreparationService, AccessTokenService, AuthSessionService {

    data class OAuthConfig(
        val clientId: String,
        val redirectUri: String,
    )

    fun interface AuthFailureListener {
        fun onAuthFailure(context: Context)
    }

    private val oauthClient = GeoVaultOAuthClient(oauthConfig.clientId, oauthConfig.redirectUri)
    private val tokenRefresh = GeoVaultTokenRefresh(store, oauthConfig.clientId, oauthClient)
    private val urlResolver = GeoVaultServerUrlResolver()
    private val userStatusClient = GeoVaultUserStatusClient(store)

    @Volatile
    private var authFailureListener: AuthFailureListener? = null

    fun setAuthFailureListener(listener: AuthFailureListener?) {
        authFailureListener = listener
    }

    override fun getServerUrl(): String = store.getServerUrl()

    fun serverUrl(): GeoVaultServerUrl? = GeoVaultServerUrl.parse(getServerUrl())

    override fun setServerUrl(url: String) {
        val normalized = normalizeServerUrl(url)
        store.setServerUrl(normalized)
    }

    override fun normalizeServerUrl(url: String): String {
        return GeoVaultServerUrl.normalize(url).orEmpty()
    }

    override fun getNormalizedServerUrl(): String = normalizeServerUrl(getServerUrl())

    override fun resolveServerUrlToCanonical(url: String): Result<String> {
        return urlResolver.resolveToCanonical(url)
    }

    fun resolveAbsoluteUrl(pathOrUrl: String): String {
        val trimmed = pathOrUrl.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        val base = serverUrl() ?: return trimmed
        return base.resolve(trimmed)
    }

    override fun isLoggedIn(): Boolean {
        val hasAccess = !store.getRawAccessToken().isNullOrBlank()
        val hasRefresh = !store.getRefreshToken().isNullOrBlank()
        return hasAccess || hasRefresh
    }

    override fun getAccessToken(): String? = store.getAccessToken()

    fun cachedAccessToken(): String? = store.getAccessToken()

    override fun getValidAccessToken(forceRefreshForToken: String?): String? {
        if (forceRefreshForToken == null) {
            val cached = store.getAccessToken()
            if (!cached.isNullOrBlank()) return cached
        }
        return tokenRefresh.refresh(forceRefreshForToken)
    }

    fun refreshAccessToken(forceRefreshForToken: String?): String? {
        return tokenRefresh.refresh(forceRefreshForToken)
    }

    override fun saveTokens(accessToken: String, refreshToken: String?, expiresInSeconds: Long) {
        store.saveTokens(accessToken, refreshToken, expiresInSeconds)
        Log.i(TAG, "saveTokens expiresInSeconds=$expiresInSeconds refreshPresent=${!refreshToken.isNullOrBlank()}")
    }

    override fun clearTokens() {
        store.clearTokens()
        GeoVaultHttp.invalidateCaches()
        Log.i(TAG, "clearTokens")
    }

    override fun generatePkcePair(): Pair<String, String> = oauthClient.generatePkcePair()

    override fun generateOAuthStateNonce(length: Int): String = oauthClient.generateOAuthStateNonce(length)

    override fun savePkceState(verifier: String, state: String) {
        store.savePkceState(verifier, state)
    }

    fun getAndClearPkceState(): Pair<String, String>? = store.getAndClearPkceState()

    fun wasRecentlyConsumedPkceState(state: String): Boolean = store.wasRecentlyConsumedPkceState(state)

    override fun buildAuthorizeUrl(serverUrl: String, codeChallenge: String, state: String): String {
        val parsed = GeoVaultServerUrl.parse(serverUrl)
            ?: error("Cannot build authorize URL without a valid server URL")
        return oauthClient.buildAuthorizeUrl(parsed, codeChallenge, state)
    }

    fun exchangeCodeForTokens(
        serverUrl: String,
        code: String,
        codeVerifier: String,
    ): Result<GeoVaultOAuthTokens> {
        val parsed = GeoVaultServerUrl.parse(serverUrl)
            ?: return Result.failure(IllegalStateException("Server URL not set"))
        return oauthClient.exchangeCodeForTokens(parsed, code, codeVerifier)
    }

    override fun getCachedUserEmail(): String? = store.getCachedUserEmail()

    override fun fetchUserStatus(callback: (String?) -> Unit) {
        callback(fetchUserStatusWithResult().email)
    }

    fun fetchUserStatusWithResult(): FetchUserStatusResult {
        if (!isLoggedIn()) {
            store.setCachedUserEmail(null)
            return FetchUserStatusResult(email = null, isUserStatusEndpointReachable = false)
        }
        return userStatusClient.fetch()
    }

    override fun revokeCurrentSession() {
        val base = serverUrl()
        if (base != null) {
            oauthClient.revokeToken(base, store.getAccessToken())
            oauthClient.revokeToken(base, store.getRefreshToken())
        }
    }

    override fun handleAuthFailure() {
        if (!isLoggedIn()) {
            Log.w(TAG, "handleAuthFailure ignored; no active auth session")
            return
        }
        Log.w(TAG, "handleAuthFailure dispatching auth reset")
        authFailureListener?.onAuthFailure(appContext)
    }

    fun launchOAuthInBrowser(context: Context, authorizeUrl: String) {
        oauthClient.launchInBrowser(context, authorizeUrl)
    }

    suspend fun probeServerTransportReachable(): Boolean {
        return GeoVaultConnectivity.probeServerReachable(getServerUrl())
    }

    companion object {
        private const val TAG = "GeoVaultAuthSession"

        const val OAUTH_CLIENT_ID_UPLOADER = "geovault-android-uploader"
        const val OAUTH_CLIENT_ID_PLACES = "geovault-android-places"
        const val OAUTH_CLIENT_ID_TRACKER = "geovault-android-tracker"
        const val OAUTH_CLIENT_ID_NGS = "geovault-android-ngs"

        @Volatile
        private var instance: GeoVaultAuthSession? = null

        fun create(
            context: Context,
            config: OAuthConfig,
            listener: AuthFailureListener? = null,
        ): GeoVaultAuthSession {
            synchronized(this) {
                instance?.let { existing ->
                    existing.setAuthFailureListener(listener)
                    return existing
                }
                val appContext = context.applicationContext
                val store = GeoVaultAuthStore.getInstance(appContext)
                store.preloadAll()
                val session = GeoVaultAuthSession(appContext, config, store)
                instance = session
                GeoVaultHttp.bind(appContext, session)
                AppResetFlow.bindTokenClear { session.clearTokens() }
                session.setAuthFailureListener(listener)
                return session
            }
        }

        fun get(): GeoVaultAuthSession {
            return instance ?: error("GeoVaultAuthSession.create() has not been called")
        }

        fun getOrNull(): GeoVaultAuthSession? = instance
    }
}
