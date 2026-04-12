package com.geovault.tracker

import android.content.Intent
import android.os.Bundle
import android.util.Log
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
        Log.i(TAG, "onCreate: intent data=${intent?.data} action=${intent?.action} flags=0x${intent?.flags?.toString(16)}")
        handleRedirect(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.i(TAG, "onNewIntent: data=${intent.data} action=${intent.action}")
        setIntent(intent)
        handleRedirect(intent)
    }

    private fun handleRedirect(intent: Intent?) {
        Log.i(TAG, "handleRedirect: dispatching to OAuthCallbackHandler")
        callbackHandler.handleIntent(
            intent = intent,
            onSuccess = {
                Log.i(TAG, "handleRedirect: OAuth succeeded, navigating to MainActivity")
                startActivity(Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                })
                finish()
            },
            onError = { message ->
                Log.e(TAG, "handleRedirect: OAuth failed — $message")
                finishWithError(message)
            },
        )
    }

    private fun finishWithError(message: String) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_OAUTH_ERROR, message)
        })
        finish()
    }

    companion object {
        private const val TAG = "OAuthCallbackActivity"
    }
}
