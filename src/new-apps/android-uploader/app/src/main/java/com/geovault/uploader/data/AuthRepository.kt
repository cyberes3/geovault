package com.geovault.uploader.data

import android.content.Context
import com.geovault.common.ServerUrlContract
import com.geovault.common.auth.AuthSessionService
import com.geovault.common.auth.GeovaultAuthServices
import com.geovault.common.auth.OAuthPreparationService
import com.geovault.common.auth.ServerConfigService
import kotlin.random.Random
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AuthRepository(
    private val serverConfigService: ServerConfigService,
    private val authSessionService: AuthSessionService,
    private val oauthPreparationService: OAuthPreparationService,
    private val peerServerUrlsProvider: () -> Set<String>
) {
    constructor(context: Context) : this(
        serverConfigService = GeovaultAuthServices(context),
        authSessionService = GeovaultAuthServices(context),
        oauthPreparationService = GeovaultAuthServices(context),
        peerServerUrlsProvider = {
            ServerUrlContract.getServerUrlsFromOtherApps(context.applicationContext)
        }
    )

    sealed interface OAuthPreparationResult {
        data class Ready(val oauthUrl: String) : OAuthPreparationResult
        data class InvalidServerUrl(val message: String) : OAuthPreparationResult
        data class UnreachableServer(val message: String) : OAuthPreparationResult
    }

    fun getNormalizedServerUrl(): String =
        serverConfigService.getNormalizedServerUrl()

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
            return OAuthPreparationResult.InvalidServerUrl(
                message = "Server URL is required. Connect your account to sign in."
            )
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
                val state = (1..16).map { "abcdef0123456789"[Random.nextInt(16)] }.joinToString("")
                oauthPreparationService.savePkceState(verifier, state)
                val oauthUrl = oauthPreparationService.buildAuthorizeUrl(resolved, challenge, state)
                OAuthPreparationResult.Ready(oauthUrl = oauthUrl)
            },
            onFailure = {
                OAuthPreparationResult.UnreachableServer(
                    message = "Could not reach server. Check URL and connection."
                )
            }
        )
    }

    fun revokeCurrentSessionTokens() {
        authSessionService.revokeCurrentSession()
    }
}
