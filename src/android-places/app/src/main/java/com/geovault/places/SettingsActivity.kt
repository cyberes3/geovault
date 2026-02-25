package com.geovault.places

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SettingsActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var serverUrlEdit: EditText
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var saveButton: Button

    private fun normalizeServerUrl(url: String): String {
        var serverUrl = url.trim().trimStart('/').trimEnd('/')
        if (serverUrl.isNotEmpty() && !serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            serverUrl = "https://$serverUrl"
        }
        return serverUrl
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        serverUrlEdit = findViewById(R.id.serverUrlEdit)
        connectButton = findViewById(R.id.connectButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        saveButton = findViewById(R.id.saveButton)

        val rootView = findViewById<View>(R.id.rootLayout)
        val headerView = findViewById<View>(R.id.headerLayout)

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            headerView.updatePadding(top = systemBars.top + 20)
            val bottomInset = if (ime.bottom > systemBars.bottom) ime.bottom else systemBars.bottom
            view.updatePadding(bottom = bottomInset)
            windowInsets
        }

        val serverUrl = GeovaultAuthManager.getServerUrl(this)
        serverUrlEdit.setText(if (serverUrl.isEmpty()) "" else serverUrl)

        updateConnectDisconnectVisibility()

        connectButton.setOnClickListener {
            val serverUrl = normalizeServerUrl(serverUrlEdit.text.toString())
            if (serverUrl.isEmpty()) {
                Toast.makeText(this, getString(R.string.settings_required), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            GeovaultAuthManager.setServerUrl(this, serverUrl)
            val (verifier, challenge) = GeovaultAuthManager.generatePkcePair()
            val state = (1..16).map { "abcdef0123456789"[kotlin.random.Random.nextInt(16)] }.joinToString("")
            GeovaultAuthManager.savePkceState(this, verifier, state)
            val authorizeUrl = GeovaultAuthManager.buildAuthorizeUrl(serverUrl, challenge, state)
            GeovaultAuthManager.launchOAuthInBrowser(this, authorizeUrl)
        }

        disconnectButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.disconnect_confirm_title))
                .setMessage(getString(R.string.disconnect_confirm_message))
                .setPositiveButton(getString(R.string.disconnect)) { _, _ ->
                    GeovaultAuthManager.clearTokens(this)
                    updateConnectDisconnectVisibility()
                    Toast.makeText(this, getString(R.string.disconnect), Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(getString(R.string.cancel_button), null)
                .show()
        }

        saveButton.setOnClickListener {
            saveSettings()
        }
    }

    override fun onResume() {
        super.onResume()
        updateConnectDisconnectVisibility()
        checkTokenStillValid()
    }

    /**
     * If we think we're logged in, verify the token with the server.
     * /api/user/status/ returns 401 when the token is invalid or revoked; then we clear local tokens.
     * Token fetch runs off the main thread (getValidAccessToken can do network I/O for refresh).
     */
    private fun checkTokenStillValid() {
        if (!GeovaultAuthManager.isLoggedIn(this)) return
        val serverUrl = normalizeServerUrl(GeovaultAuthManager.getServerUrl(this))
        if (serverUrl.isBlank()) return
        executor.execute {
            val token = GeovaultAuthManager.getValidAccessToken(this@SettingsActivity) ?: return@execute
            runOnUiThread {
                val request = Request.Builder()
                    .url("$serverUrl/api/user/status/")
                    .addHeader("Authorization", "Bearer $token")
                    .build()
                OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
                    .newCall(request)
                    .enqueue(object : Callback {
                        override fun onFailure(call: Call, e: java.io.IOException) {}
                        override fun onResponse(call: Call, response: Response) {
                            val code = response.code
                            response.close()
                            if (code == 401) {
                                runOnUiThread {
                                    GeovaultAuthManager.clearTokens(this@SettingsActivity)
                                    updateConnectDisconnectVisibility()
                                }
                            }
                        }
                    })
            }
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private fun updateConnectDisconnectVisibility() {
        val loggedIn = GeovaultAuthManager.isLoggedIn(this)
        connectButton.visibility = if (loggedIn) View.GONE else View.VISIBLE
        disconnectButton.visibility = if (loggedIn) View.VISIBLE else View.GONE
    }

    private fun saveSettings() {
        val serverUrl = normalizeServerUrl(serverUrlEdit.text.toString())
        if (serverUrl.isEmpty()) {
            Toast.makeText(this, getString(R.string.settings_required), Toast.LENGTH_LONG).show()
            return
        }
        GeovaultAuthManager.setServerUrl(this, serverUrl)
        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    override fun finish() {
        super.finish()
        safeNoAnimation()
    }

    private fun safeNoAnimation() {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
