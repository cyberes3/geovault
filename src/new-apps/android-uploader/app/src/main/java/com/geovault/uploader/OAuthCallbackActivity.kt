package com.geovault.uploader

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.geovault.common.auth.OAuthCallbackHandler

class OAuthCallbackActivity : ComponentActivity() {
    private val callbackHandler by lazy {
        OAuthCallbackHandler(
            context = this,
            postToMain = { block -> runOnUiThread(block) },
        )
    }

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
        callbackHandler.handleIntent(
            intent = intent,
            onSuccess = {
                if (isDestroyed) return@handleIntent
                Toast.makeText(this, "Connected successfully", Toast.LENGTH_SHORT).show()
                finish()
            },
            onError = { message ->
                if (isDestroyed) return@handleIntent
                finishWithError(message)
            }
        )
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
