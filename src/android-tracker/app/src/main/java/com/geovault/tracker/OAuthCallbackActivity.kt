package com.geovault.tracker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.geovault.common.GeovaultAuthManager
import java.util.concurrent.Executors

class OAuthCallbackActivity : AppCompatActivity() {

    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleRedirect(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRedirect(intent)
    }

    private fun handleRedirect(intent: Intent?) {
        val uri: Uri? = intent?.data
        if (uri == null) {
            finishWithError("No redirect data")
            return
        }
        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")
        if (code.isNullOrBlank()) {
            val error = uri.getQueryParameter("error") ?: "No authorization code"
            finishWithError(error)
            return
        }
        val pkce = GeovaultAuthManager.getAndClearPkceState(this)
        if (pkce == null || pkce.second != state) {
            finishWithError("Invalid state")
            return
        }
        val serverUrl = GeovaultAuthManager.getServerUrl(this)
        if (serverUrl.isBlank()) {
            finishWithError("Server URL not set")
            return
        }

        executor.execute {
            try {
                GeovaultAuthManager.exchangeCodeForTokens(
                    serverUrl,
                    code,
                    pkce.first,
                    onSuccess = { accessToken, refreshToken, expiresIn ->
                        runOnUiThread {
                            if (isDestroyed) return@runOnUiThread
                            GeovaultAuthManager.saveTokens(this@OAuthCallbackActivity, accessToken, refreshToken, expiresIn)
                            finish(signedInEmail = null)
                        }
                    },
                    onError = { msg ->
                        runOnUiThread {
                            if (isDestroyed) return@runOnUiThread
                            finish(signedInEmail = null, errorMessage = msg)
                        }
                    }
                )
            } catch (e: Exception) {
                runOnUiThread {
                    finish(signedInEmail = null, errorMessage = "Error: ${e.message}")
                }
            }
        }
    }

    private fun finishWithError(msg: String) {
        finish(signedInEmail = null, errorMessage = msg)
    }

    override fun finish() {
        finish(signedInEmail = null)
    }

    private fun finish(signedInEmail: String?, errorMessage: String? = null) {
        val mainIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        signedInEmail?.let { mainIntent.putExtra(MainActivity.EXTRA_SIGNED_IN_EMAIL, it) }
        errorMessage?.let { mainIntent.putExtra(MainActivity.EXTRA_OAUTH_ERROR, it) }
        startActivity(mainIntent)
        super.finish()
    }
}
