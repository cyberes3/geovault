package com.geovault.common.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
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
        val callbackState = input.state?.trim()
        val savedStateForLog = input.pkceState?.second?.trim()
        Log.d(TAG, "validate: code=${if (input.code.isNullOrBlank()) "MISSING" else "present(${input.code.length} chars)"}" +
            " state=${if (callbackState.isNullOrBlank()) "MISSING" else "present($callbackState)"}" +
            " oauthError=${input.oauthError}" +
            " pkceState=${if (input.pkceState == null) "NULL" else "present(savedState=$savedStateForLog)"}" +
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
        val expectedState = pkce.second.trim()
        if (expectedState != callbackState) {
            Log.e(
                TAG,
                "validate FAILED: state mismatch — callback state=$callbackState stored state=$expectedState"
            )
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

/**
 * Read OAuth2 callback parameters from the redirect URI query, and (if any are missing) from
 * a fragment in `key=value&` form. Some handoff flows put parameters only in the fragment
 * (and [Uri.getQueryParameter] on the full URI will not read them from there).
 */
internal fun parseOAuthRedirectParams(uri: Uri): Triple<String?, String?, String?> {
    var code = uri.getQueryParameter("code")
    var state = uri.getQueryParameter("state")
    var error = uri.getQueryParameter("error")
    if (code == null || state == null || error == null) {
        val fromFragment = uri.fragment?.let { parseFragmentKeyValues(it) }
        if (fromFragment != null) {
            if (code == null) code = fromFragment["code"]
            if (state == null) state = fromFragment["state"]
            if (error == null) error = fromFragment["error"]
        }
    }
    return Triple(code, state, error)
}

private fun parseFragmentKeyValues(fragment: String): Map<String, String>? {
    val q = fragment.trim().removePrefix("?").removePrefix("/")
    if (q.isEmpty() || !q.contains('=')) return null
    val parsed = Uri.parse("https://oauth-fragment.local/blank?$q")
    if (parsed.query.isNullOrBlank()) return null
    return parsed.queryParameterNames.associateWith { n -> parsed.getQueryParameter(n) ?: "" }
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
        onDuplicate: () -> Unit,
    ) {
        Log.i(TAG, "handleIntent: intent action=${intent?.action} data=${intent?.data}")
        val uri: Uri = intent?.data ?: run {
            Log.w(TAG, "handleIntent: no redirect data in intent")
            onError("No redirect data")
            return
        }
        val session = GeoVaultAuthSession.get()
        Log.d(TAG, "handleIntent: callback URI scheme=${uri.scheme} host=${uri.host} path=${uri.path}" +
            " queryParams=${uri.queryParameterNames} fragmentLen=${uri.fragment?.length ?: 0}")
        val (code, state, oauthError) = parseOAuthRedirectParams(uri)
        val pkceState = session.getAndClearPkceState()
        val serverUrl = session.getServerUrl()
        if (pkceState == null &&
            !state.isNullOrBlank() &&
            session.wasRecentlyConsumedPkceState(state)
        ) {
            Log.i(TAG, "handleIntent: ignoring duplicate callback for already-consumed state=$state")
            onDuplicate()
            return
        }

        val validation = OAuthCallbackValidator.validate(
            OAuthCallbackValidationInput(
                code = code,
                state = state,
                oauthError = oauthError,
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
                    session = session,
                    ready = validation,
                    onSuccess = onSuccess,
                    onError = onError,
                )
            }
        }
    }

    private fun executeTokenExchange(
        session: GeoVaultAuthSession,
        ready: OAuthCallbackValidationResult.Ready,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        Log.i(TAG, "executeTokenExchange: server=${ready.serverUrl}")
        executor.execute {
            val result = session.exchangeCodeForTokens(
                serverUrl = ready.serverUrl,
                code = ready.code,
                codeVerifier = ready.codeVerifier,
            )
            result.fold(
                onSuccess = { tokens ->
                    Log.i(TAG, "executeTokenExchange: success, expiresIn=${tokens.expiresInSeconds}s")
                    postToMain {
                        session.saveTokens(tokens.accessToken, tokens.refreshToken, tokens.expiresInSeconds)
                        onSuccess()
                    }
                },
                onFailure = { error ->
                    val message = error.message ?: "Token exchange failed"
                    Log.e(TAG, "executeTokenExchange: failed — $message")
                    postToMain { onError(message) }
                },
            )
        }
    }
}
