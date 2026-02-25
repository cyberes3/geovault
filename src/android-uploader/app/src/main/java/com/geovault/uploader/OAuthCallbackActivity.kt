package com.geovault.uploader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
            GeovaultAuthManager.exchangeCodeForTokens(
                serverUrl,
                code,
                pkce.first,
                onSuccess = { accessToken, refreshToken, expiresIn ->
                    runOnUiThread {
                        GeovaultAuthManager.saveTokens(this@OAuthCallbackActivity, accessToken, refreshToken, expiresIn)
                        setResult(RESULT_OK)
                        Toast.makeText(this@OAuthCallbackActivity, getString(R.string.oauth_connected), Toast.LENGTH_SHORT).show()
                        finish()
                    }
                },
                onError = { msg ->
                    runOnUiThread {
                        Toast.makeText(this@OAuthCallbackActivity, msg, Toast.LENGTH_LONG).show()
                        setResult(RESULT_CANCELED)
                        finish()
                    }
                }
            )
        }
    }

    private fun finishWithError(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        setResult(RESULT_CANCELED)
        finish()
    }

    override fun finish() {
        super.finish()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }
}
