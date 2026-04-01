package com.geovault.common.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.geovault.common.GeovaultAuthManager
import java.util.concurrent.Executor
import java.util.concurrent.Executors

data class OAuthCallbackValidationInput(
    val code: String?,
    val state: String?,
    val oauthError: String?,
    val pkceState: Pair<String, String>?,
    val serverUrl: String,
)

sealed interface OAuthCallbackValidationResult {
    data class Ready(
        val code: String,
        val codeVerifier: String,
        val serverUrl: String,
    ) : OAuthCallbackValidationResult

    data class Error(val message: String) : OAuthCallbackValidationResult
}

object OAuthCallbackValidator {
    fun validate(input: OAuthCallbackValidationInput): OAuthCallbackValidationResult {
        if (input.code.isNullOrBlank()) {
            return OAuthCallbackValidationResult.Error(
                message = input.oauthError ?: "No authorization code"
            )
        }
        val pkce = input.pkceState
            ?: return OAuthCallbackValidationResult.Error(message = "Invalid state")
        if (pkce.second != input.state) {
            return OAuthCallbackValidationResult.Error(message = "Invalid state")
        }
        if (input.serverUrl.isBlank()) {
            return OAuthCallbackValidationResult.Error(message = "Server URL not set")
        }
        return OAuthCallbackValidationResult.Ready(
            code = input.code,
            codeVerifier = pkce.first,
            serverUrl = input.serverUrl,
        )
    }
}

class OAuthCallbackHandler(
    private val context: Context,
    private val executor: Executor = Executors.newSingleThreadExecutor(),
    private val postToMain: ((() -> Unit) -> Unit),
) {
    fun handleIntent(
        intent: Intent?,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val uri: Uri = intent?.data ?: run {
            onError("No redirect data")
            return
        }
        val validation = OAuthCallbackValidator.validate(
            OAuthCallbackValidationInput(
                code = uri.getQueryParameter("code"),
                state = uri.getQueryParameter("state"),
                oauthError = uri.getQueryParameter("error"),
                pkceState = GeovaultAuthManager.getAndClearPkceState(context),
                serverUrl = GeovaultAuthManager.getServerUrl(context),
            )
        )
        when (validation) {
            is OAuthCallbackValidationResult.Error -> onError(validation.message)
            is OAuthCallbackValidationResult.Ready -> executeTokenExchange(
                ready = validation,
                onSuccess = onSuccess,
                onError = onError,
            )
        }
    }

    private fun executeTokenExchange(
        ready: OAuthCallbackValidationResult.Ready,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        executor.execute {
            GeovaultAuthManager.exchangeCodeForTokens(
                serverUrl = ready.serverUrl,
                code = ready.code,
                codeVerifier = ready.codeVerifier,
                onSuccess = { accessToken, refreshToken, expiresIn ->
                    postToMain {
                        GeovaultAuthManager.saveTokens(context, accessToken, refreshToken, expiresIn)
                        onSuccess()
                    }
                },
                onError = { message ->
                    postToMain { onError(message) }
                }
            )
        }
    }
}
