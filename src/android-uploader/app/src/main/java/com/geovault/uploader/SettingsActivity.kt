package com.geovault.uploader

import android.content.Context
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.RetrofitClient
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

class SettingsActivity : AppCompatActivity() {
    private lateinit var serverUrlEdit: EditText
    private lateinit var connectButton: MaterialButton
    private lateinit var disconnectButton: MaterialButton
    private lateinit var addSuffixCheckbox: CheckBox
    private lateinit var saveButton: MaterialButton

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
    }

    companion object {
        private const val PREF_ADD_SUFFIX = "add_suffix"
    }

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
        addSuffixCheckbox = findViewById(R.id.addSuffixCheckbox)
        saveButton = findViewById(R.id.saveButton)

        val rootView = findViewById<View>(R.id.rootLayout)
        val headerView = findViewById<View>(R.id.headerLayout)

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            headerView.updatePadding(top = insets.top + 20)
            WindowInsetsCompat.CONSUMED
        }

        serverUrlEdit.setText(GeovaultAuthManager.getServerUrl(this))
        addSuffixCheckbox.isChecked = prefs.getBoolean(PREF_ADD_SUFFIX, true)

        updateConnectDisconnectVisibility()

        connectButton.setOnClickListener {
            val serverUrl = normalizeServerUrl(serverUrlEdit.text.toString())
            if (serverUrl.isEmpty()) {
                Toast.makeText(this, getString(R.string.settings_required), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            GeovaultAuthManager.setServerUrl(this, serverUrl, commit = true)
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
                    GeovaultAuthManager.revokeToken(this, GeovaultAuthManager.getAccessToken(this))
                    GeovaultAuthManager.revokeToken(this, GeovaultAuthManager.getRefreshToken(this))
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
     * Uses auth-aware client so expired tokens are refreshed and retried; only clear tokens if still 401 after retry.
     */
    private fun checkTokenStillValid() {
        if (!GeovaultAuthManager.isLoggedIn(this)) return
        val serverUrl = normalizeServerUrl(GeovaultAuthManager.getServerUrl(this))
        if (serverUrl.isBlank()) return
        val client = RetrofitClient.getAuthenticatedOkHttpClient(this)
            .newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url("$serverUrl/api/user/status/").build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                response.close()
                if (code == 401) {
                    runOnUiThread {
                        if (!isDestroyed) {
                            resetOnAuthFailure(this@SettingsActivity)
                        }
                    }
                }
            }
        })
    }

    override fun onDestroy() {
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
        prefs.edit().putBoolean(PREF_ADD_SUFFIX, addSuffixCheckbox.isChecked).apply()
        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }
}
