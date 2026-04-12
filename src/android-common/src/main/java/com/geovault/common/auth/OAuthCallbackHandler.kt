package com.geovault.common.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.geovault.common.GeovaultAuthManager
import java.util.concurrent.Executor
import java.util.concurrent.Executors

private const val TAG = "OAuthCallback"

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
        Log.d(TAG, "validate: code=${if (input.code.isNullOrBlank()) "MISSING" else "present(${input.code.length} chars)"}" +
            " state=${if (input.state.isNullOrBlank()) "MISSING" else "present(${input.state})"}" +
            " oauthError=${input.oauthError}" +
            " pkceState=${if (input.pkceState == null) "NULL" else "present(savedState=${input.pkceState.second})"}" +
            " serverUrl=${input.serverUrl.ifBlank { "BLANK" }}")

        if (input.code.isNullOrBlank()) {
            val msg = input.oauthError ?: "No authorization code"
            Log.w(TAG, "validate FAILED: no authorization code, oauthError=$msg")
            return OAuthCallbackValidationResult.Error(message = msg)
        }
        val pkce = input.pkceState
        if (pkce == null) {
            Log.e(TAG, "validate FAILED: pkceState is null — stored PKCE was missing or decryption failed")
            return OAuthCallbackValidationResult.Error(message = "Invalid state")
        }
        if (pkce.second != input.state) {
            Log.e(TAG, "validate FAILED: state mismatch — callback state=${input.state} stored state=${pkce.second}")
            return OAuthCallbackValidationResult.Error(message = "Invalid state")
        }
        if (input.serverUrl.isBlank()) {
            Log.e(TAG, "validate FAILED: server URL is blank")
            return OAuthCallbackValidationResult.Error(message = "Server URL not set")
        }
        Log.i(TAG, "validate OK: proceeding to token exchange against ${input.serverUrl}")
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
        Log.i(TAG, "handleIntent: intent action=${intent?.action} data=${intent?.data}")
        val uri: Uri = intent?.data ?: run {
            Log.w(TAG, "handleIntent: no redirect data in intent")
            onError("No redirect data")
            return
        }
        Log.d(TAG, "handleIntent: callback URI scheme=${uri.scheme} host=${uri.host} path=${uri.path}" +
            " queryParams=${uri.queryParameterNames}")
        val pkceState = GeovaultAuthManager.getAndClearPkceState(context)
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        Log.d(TAG, "handleIntent: retrieved pkceState=${if (pkceState == null) "NULL" else "present"}" +
            " serverUrl=${serverUrl.ifBlank { "BLANK" }}")

        val validation = OAuthCallbackValidator.validate(
            OAuthCallbackValidationInput(
                code = uri.getQueryParameter("code"),
                state = uri.getQueryParameter("state"),
                oauthError = uri.getQueryParameter("error"),
                pkceState = pkceState,
                serverUrl = serverUrl,
            )
        )
        when (validation) {
            is OAuthCallbackValidationResult.Error -> {
                Log.w(TAG, "handleIntent: validation failed — ${validation.message}")
                onError(validation.message)
            }
            is OAuthCallbackValidationResult.Ready -> {
                Log.i(TAG, "handleIntent: validation passed, starting token exchange")
                executeTokenExchange(
                    ready = validation,
                    onSuccess = onSuccess,
                    onError = onError,
                )
            }
        }
    }

    private fun executeTokenExchange(
        ready: OAuthCallbackValidationResult.Ready,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        Log.i(TAG, "executeTokenExchange: server=${ready.serverUrl}")
        executor.execute {
            GeovaultAuthManager.exchangeCodeForTokens(
                serverUrl = ready.serverUrl,
                code = ready.code,
                codeVerifier = ready.codeVerifier,
                onSuccess = { accessToken, refreshToken, expiresIn ->
                    Log.i(TAG, "executeTokenExchange: success, expiresIn=${expiresIn}s refreshPresent=${!refreshToken.isNullOrBlank()}")
                    postToMain {
                        GeovaultAuthManager.saveTokens(context, accessToken, refreshToken, expiresIn)
                        onSuccess()
                    }
                },
                onError = { message ->
                    Log.e(TAG, "executeTokenExchange: failed — $message")
                    postToMain { onError(message) }
                }
            )
        }
    }
}
