package com.geovault.common.auth

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "InitialAuthController"

class CommonInitialAuthController(
    private val serverConfigService: ServerConfigService,
    private val authSessionService: AuthSessionService,
    private val oauthPreparationService: OAuthPreparationService,
    private val peerServerUrlsProvider: () -> Set<String>,
    private val invalidServerUrlMessage: String = "Server URL is required.",
    private val unreachableServerMessage: String = "Could not reach server.",
) {
    sealed interface OAuthPreparationResult {
        data class Ready(val oauthUrl: String) : OAuthPreparationResult
        data class InvalidServerUrl(val message: String) : OAuthPreparationResult
        data class UnreachableServer(val message: String) : OAuthPreparationResult
    }

    fun getNormalizedServerUrl(): String = serverConfigService.getNormalizedServerUrl()

    fun getConfiguredServerUrlOrPeerDefault(): String {
        return serverConfigService.getServerUrl().ifBlank {
            peerServerUrlsProvider().singleOrNull().orEmpty()
        }
    }

    fun setServerUrl(url: String, commit: Boolean = false) {
        serverConfigService.setServerUrl(url, commit = commit)
    }

    fun isLoggedIn(): Boolean = authSessionService.isLoggedIn()

    fun getCachedUserEmail(): String? = authSessionService.getCachedUserEmail()

    fun fetchUserEmail(callback: (String?) -> Unit) {
        authSessionService.fetchUserStatus(callback)
    }

    suspend fun prepareOAuthConnection(rawServerUrl: String): OAuthPreparationResult {
        Log.i(TAG, "prepareOAuthConnection: rawServerUrl=$rawServerUrl")
        val normalized = serverConfigService.normalizeServerUrl(rawServerUrl)
        if (normalized.isBlank()) {
            Log.w(TAG, "prepareOAuthConnection: normalized URL is blank")
            return OAuthPreparationResult.InvalidServerUrl(message = invalidServerUrlMessage)
        }
        Log.d(TAG, "prepareOAuthConnection: normalized=$normalized, resolving to canonical…")
        val resolvedResult = suspendCancellableCoroutine<Result<String>> { continuation ->
            serverConfigService.resolveServerUrlToCanonical(normalized) { result ->
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }
        }
        return resolvedResult.fold(
            onSuccess = { resolved ->
                Log.i(TAG, "prepareOAuthConnection: resolved server=$resolved")
                serverConfigService.setServerUrl(resolved, commit = true)
                val (verifier, challenge) = oauthPreparationService.generatePkcePair()
                val state = oauthPreparationService.generateOAuthStateNonce(length = 16)
                Log.d(TAG, "prepareOAuthConnection: generated PKCE state=$state, saving…")
                oauthPreparationService.savePkceState(verifier, state)
                val oauthUrl = oauthPreparationService.buildAuthorizeUrl(resolved, challenge, state)
                Log.i(TAG, "prepareOAuthConnection: ready, authorize URL host=${Uri.parse(oauthUrl).host}")
                OAuthPreparationResult.Ready(oauthUrl = oauthUrl)
            },
            onFailure = { e ->
                Log.w(TAG, "prepareOAuthConnection: server unreachable — ${e.javaClass.simpleName}: ${e.message}")
                OAuthPreparationResult.UnreachableServer(message = unreachableServerMessage)
            }
        )
    }

    fun revokeCurrentSessionTokens() {
        Log.i(TAG, "revokeCurrentSessionTokens")
        authSessionService.revokeCurrentSession()
    }
}
