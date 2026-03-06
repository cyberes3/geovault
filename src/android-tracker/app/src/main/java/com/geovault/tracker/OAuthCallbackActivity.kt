package com.geovault.tracker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
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
                            TrackerApplication.prefetchIfNeeded(applicationContext)
                            GeovaultAuthManager.fetchUserStatus(this@OAuthCallbackActivity) { email ->
                                runOnUiThread {
                                    finish(signedInEmail = email)
                                }
                            }
                        }
                    },
                    onError = { msg ->
                        runOnUiThread {
                            if (isDestroyed) return@runOnUiThread
                            Toast.makeText(this@OAuthCallbackActivity, msg, Toast.LENGTH_LONG).show()
                            finish()
                        }
                    }
                )
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@OAuthCallbackActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun finishWithError(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun finish() {
        finish(signedInEmail = null)
    }

    private fun finish(signedInEmail: String?) {
        val mainIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        signedInEmail?.let { mainIntent.putExtra(MainActivity.EXTRA_SIGNED_IN_EMAIL, it) }
        startActivity(mainIntent)
        super.finish()
    }
}
