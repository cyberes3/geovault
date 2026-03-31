package com.geovault.uploader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.geovault.common.GeovaultAuthManager
import java.util.concurrent.Executors

class OAuthCallbackActivity : ComponentActivity() {
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
        val uri: Uri = intent?.data ?: run {
            finishWithError("No redirect data")
            return
        }
        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")
        if (code.isNullOrBlank()) {
            finishWithError(uri.getQueryParameter("error") ?: "No authorization code")
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
            GeovaultAuthManager.exchangeCodeForTokens(
                serverUrl = serverUrl,
                code = code,
                codeVerifier = pkce.first,
                onSuccess = { accessToken, refreshToken, expiresIn ->
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        GeovaultAuthManager.saveTokens(this, accessToken, refreshToken, expiresIn)
                        Toast.makeText(this, "Connected successfully", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                },
                onError = { message ->
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        finishWithError(message)
                    }
                }
            )
        }
    }

    private fun finishWithError(message: String) {
        val next = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_OAUTH_ERROR, message)
        }
        startActivity(next)
        finish()
    }
}
