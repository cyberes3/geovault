package com.geovault.common.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity

/**
 * Shared base class for per-app `OAuthCallbackActivity` implementations. Subclasses provide a
 * [mainActivityClass] and optionally override [onOAuthSuccess] / [onOAuthError] for
 * app-specific navigation.
 *
 * Default behaviour:
 * - On success, starts [mainActivityClass] with `FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP`
 *   and finishes this activity.
 * - On error, starts [mainActivityClass] with the same flags plus
 *   [GeoVaultAuthExtras.OAUTH_ERROR_EXTRA_KEY] set to the message, and finishes this activity.
 *
 * Callback handling itself is delegated to [OAuthCallbackHandler]; setting [logTag] to a
 * non-null value produces the same lifecycle logs as the pre-template tracker implementation.
 */
abstract class GeoVaultOAuthCallbackActivityTemplate : ComponentActivity() {

    /** Concrete main activity class used for both success and error navigation. */
    protected abstract val mainActivityClass: Class<out Activity>

    /** Set to a non-null value to emit the tracker-style lifecycle logs at each step. */
    protected open val logTag: String? = null

    private val callbackHandler by lazy {
        OAuthCallbackHandler(
            context = this,
            postToMain = { block -> runOnUiThread(block) },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logTag?.let {
            Log.i(
                it,
                "onCreate: intent data=${intent?.data} action=${intent?.action}" +
                    " flags=0x${intent?.flags?.toString(16)}",
            )
        }
        handleRedirect(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        logTag?.let { Log.i(it, "onNewIntent: data=${intent.data} action=${intent.action}") }
        setIntent(intent)
        handleRedirect(intent)
    }

    /**
     * Default success handler: navigate to [mainActivityClass] and finish. Override to change
     * the post-success UX (e.g. show a toast and return to the caller stack, as the uploader
     * does).
     */
    protected open fun onOAuthSuccess() {
        if (isDestroyed) return
        startActivity(
            Intent(this, mainActivityClass).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
        finish()
    }

    /**
     * Default error handler: route [message] to [mainActivityClass] via
     * [GeoVaultAuthExtras.OAUTH_ERROR_EXTRA_KEY] and finish. Override to present the error
     * locally (e.g. in-place toast) instead.
     */
    protected open fun onOAuthError(message: String) {
        if (isDestroyed) return
        val next = Intent(this, mainActivityClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(GeoVaultAuthExtras.OAUTH_ERROR_EXTRA_KEY, message)
        }
        startActivity(next)
        finish()
    }

    private fun handleRedirect(intent: Intent?) {
        logTag?.let { Log.i(it, "handleRedirect: dispatching to OAuthCallbackHandler") }
        callbackHandler.handleIntent(
            intent = intent,
            onSuccess = {
                logTag?.let { Log.i(it, "handleRedirect: OAuth succeeded") }
                onOAuthSuccess()
            },
            onError = { message ->
                logTag?.let { Log.e(it, "handleRedirect: OAuth failed - $message") }
                onOAuthError(message)
            },
            onDuplicate = {
                logTag?.let { Log.i(it, "handleRedirect: duplicate OAuth callback ignored") }
                finish()
            },
        )
    }
}
