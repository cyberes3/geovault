package com.geovault.common.auth

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

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
        val normalized = serverConfigService.normalizeServerUrl(rawServerUrl)
        if (normalized.isBlank()) {
            return OAuthPreparationResult.InvalidServerUrl(message = invalidServerUrlMessage)
        }
        val resolvedResult = suspendCancellableCoroutine<Result<String>> { continuation ->
            serverConfigService.resolveServerUrlToCanonical(normalized) { result ->
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }
        }
        return resolvedResult.fold(
            onSuccess = { resolved ->
                serverConfigService.setServerUrl(resolved, commit = true)
                val (verifier, challenge) = oauthPreparationService.generatePkcePair()
                val state = oauthPreparationService.generateOAuthStateNonce(length = 16)
                oauthPreparationService.savePkceState(verifier, state)
                val oauthUrl = oauthPreparationService.buildAuthorizeUrl(resolved, challenge, state)
                OAuthPreparationResult.Ready(oauthUrl = oauthUrl)
            },
            onFailure = {
                OAuthPreparationResult.UnreachableServer(message = unreachableServerMessage)
            }
        )
    }

    fun revokeCurrentSessionTokens() {
        authSessionService.revokeCurrentSession()
    }
}
