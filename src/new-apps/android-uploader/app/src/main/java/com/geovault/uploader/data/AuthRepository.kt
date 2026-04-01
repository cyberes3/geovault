package com.geovault.uploader.data

import android.content.Context
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.ServerUrlContract
import kotlin.random.Random
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AuthRepository(private val context: Context) {
    sealed interface OAuthPreparationResult {
        data class Ready(val oauthUrl: String) : OAuthPreparationResult
        data class InvalidServerUrl(val message: String) : OAuthPreparationResult
        data class UnreachableServer(val message: String) : OAuthPreparationResult
    }

    fun getNormalizedServerUrl(): String =
        GeovaultAuthManager.normalizeServerUrl(GeovaultAuthManager.getServerUrl(context))

    fun getConfiguredServerUrlOrPeerDefault(): String {
        return GeovaultAuthManager.getServerUrl(context).ifBlank {
            ServerUrlContract.getServerUrlsFromOtherApps(context).singleOrNull().orEmpty()
        }
    }

    fun setServerUrl(url: String, commit: Boolean = false) {
        GeovaultAuthManager.setServerUrl(context, url, commit = commit)
    }

    fun isLoggedIn(): Boolean = GeovaultAuthManager.isLoggedIn(context)

    fun getCachedUserEmail(): String? = GeovaultAuthManager.getCachedUserEmail(context)

    fun fetchUserEmail(callback: (String?) -> Unit) {
        GeovaultAuthManager.fetchUserStatus(context, callback)
    }

    suspend fun prepareOAuthConnection(rawServerUrl: String): OAuthPreparationResult {
        val normalized = GeovaultAuthManager.normalizeServerUrl(rawServerUrl)
        if (normalized.isBlank()) {
            return OAuthPreparationResult.InvalidServerUrl(
                message = "Server URL is required. Connect your account to sign in."
            )
        }
        val resolvedResult = suspendCancellableCoroutine<Result<String>> { continuation ->
            GeovaultAuthManager.resolveServerUrlToCanonical(normalized) { result ->
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }
        }
        return resolvedResult.fold(
            onSuccess = { resolved ->
                GeovaultAuthManager.setServerUrl(context, resolved, commit = true)
                val (verifier, challenge) = GeovaultAuthManager.generatePkcePair()
                val state = (1..16).map { "abcdef0123456789"[Random.nextInt(16)] }.joinToString("")
                GeovaultAuthManager.savePkceState(context, verifier, state)
                val oauthUrl = GeovaultAuthManager.buildAuthorizeUrl(resolved, challenge, state)
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
        GeovaultAuthManager.revokeCurrentSession(context)
    }
}
