package com.geovault.common.auth

import android.content.Context
import com.geovault.common.GeovaultAuthManager

interface ServerConfigService {
    fun getServerUrl(): String
    fun setServerUrl(url: String, commit: Boolean = false)
    fun normalizeServerUrl(url: String): String
    fun getNormalizedServerUrl(): String
    fun resolveServerUrlToCanonical(url: String, callback: (Result<String>) -> Unit)
}

interface OAuthPreparationService {
    fun generatePkcePair(): Pair<String, String>
    fun generateOAuthStateNonce(length: Int = 16): String
    fun savePkceState(verifier: String, state: String)
    fun buildAuthorizeUrl(serverUrl: String, codeChallenge: String, state: String): String
}

interface AccessTokenService {
    fun getAccessToken(): String?
    fun getValidAccessToken(forceRefreshForToken: String? = null): String?
    fun saveTokens(accessToken: String, refreshToken: String?, expiresInSeconds: Long)
    fun clearTokens()
}

interface AuthSessionService {
    fun isLoggedIn(): Boolean
    fun getCachedUserEmail(): String?
    fun fetchUserStatus(callback: (String?) -> Unit)
    fun revokeCurrentSession()
    fun handleAuthFailure()
}

class GeovaultAuthServices(context: Context) :
    ServerConfigService,
    OAuthPreparationService,
    AccessTokenService,
    AuthSessionService {

    private val appContext = context.applicationContext

    override fun getServerUrl(): String = GeovaultAuthManager.getServerUrl(appContext)

    override fun setServerUrl(url: String, commit: Boolean) {
        GeovaultAuthManager.setServerUrl(appContext, url, commit = commit)
    }

    override fun normalizeServerUrl(url: String): String = GeovaultAuthManager.normalizeServerUrl(url)

    override fun getNormalizedServerUrl(): String {
        return normalizeServerUrl(getServerUrl())
    }

    override fun resolveServerUrlToCanonical(url: String, callback: (Result<String>) -> Unit) {
        GeovaultAuthManager.resolveServerUrlToCanonical(url, callback)
    }

    override fun generatePkcePair(): Pair<String, String> = GeovaultAuthManager.generatePkcePair()

    override fun generateOAuthStateNonce(length: Int): String {
        return GeovaultAuthManager.generateOAuthStateNonce(length = length)
    }

    override fun savePkceState(verifier: String, state: String) {
        GeovaultAuthManager.savePkceState(appContext, verifier, state)
    }

    override fun buildAuthorizeUrl(serverUrl: String, codeChallenge: String, state: String): String {
        return GeovaultAuthManager.buildAuthorizeUrl(serverUrl, codeChallenge, state)
    }

    override fun getAccessToken(): String? = GeovaultAuthManager.getAccessToken(appContext)

    override fun getValidAccessToken(forceRefreshForToken: String?): String? {
        return GeovaultAuthManager.getValidAccessToken(appContext, forceRefreshForToken)
    }

    override fun saveTokens(accessToken: String, refreshToken: String?, expiresInSeconds: Long) {
        GeovaultAuthManager.saveTokens(
            context = appContext,
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresInSeconds = expiresInSeconds
        )
    }

    override fun clearTokens() {
        GeovaultAuthManager.clearTokens(appContext)
    }

    override fun isLoggedIn(): Boolean = GeovaultAuthManager.isLoggedIn(appContext)

    override fun getCachedUserEmail(): String? = GeovaultAuthManager.getCachedUserEmail(appContext)

    override fun fetchUserStatus(callback: (String?) -> Unit) {
        GeovaultAuthManager.fetchUserStatus(appContext, callback)
    }

    override fun revokeCurrentSession() {
        GeovaultAuthManager.revokeCurrentSession(appContext)
    }

    override fun handleAuthFailure() {
        GeovaultAuthManager.handleAuthFailure(appContext)
    }
}
