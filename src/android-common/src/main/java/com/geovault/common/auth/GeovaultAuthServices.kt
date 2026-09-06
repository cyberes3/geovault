package com.geovault.common.auth

interface ServerConfigService {
    fun getServerUrl(): String
    fun setServerUrl(url: String)
    fun normalizeServerUrl(url: String): String
    fun getNormalizedServerUrl(): String
    fun resolveServerUrlToCanonical(url: String): Result<String>
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
