package com.geovault.places

import android.content.Intent
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.RetrofitClient
import com.geovault.common.ServerUrlContract
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.geovault.common.AppResetFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

class SettingsActivity : AppCompatActivity() {
    private lateinit var serverUrlEdit: EditText
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var loggedInUserText: TextView
    private lateinit var settingsHelpText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        serverUrlEdit = findViewById(R.id.serverUrlEdit)
        connectButton = findViewById(R.id.connectButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        loggedInUserText = findViewById(R.id.loggedInUserText)
        settingsHelpText = findViewById(R.id.settingsHelpText)

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
        if (serverUrl.isNotEmpty()) {
            serverUrlEdit.setText(serverUrl)
        } else {
            val otherUrls = ServerUrlContract.getServerUrlsFromOtherApps(this)
            if (otherUrls.size == 1) {
                serverUrlEdit.setText(otherUrls.single())
            } else {
                serverUrlEdit.setText("")
            }
        }

        updateConnectDisconnectVisibility()

        connectButton.setOnClickListener {
            val url = GeovaultAuthManager.normalizeServerUrl(serverUrlEdit.text.toString())
            if (url.isEmpty()) {
                Toast.makeText(this, getString(R.string.settings_required), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            connectButton.isEnabled = false
            Toast.makeText(this, getString(R.string.connecting_server), Toast.LENGTH_SHORT).show()
            GeovaultAuthManager.resolveServerUrlToCanonical(url) { result ->
                runOnUiThread {
                    connectButton.isEnabled = true
                    result.fold(
                        onSuccess = { resolvedUrl ->
                            GeovaultAuthManager.setServerUrl(this, resolvedUrl, commit = true)
                            val (verifier, challenge) = GeovaultAuthManager.generatePkcePair()
                            val state = (1..16).map { "abcdef0123456789"[kotlin.random.Random.nextInt(16)] }.joinToString("")
                            GeovaultAuthManager.savePkceState(this, verifier, state)
                            val authorizeUrl = GeovaultAuthManager.buildAuthorizeUrl(resolvedUrl, challenge, state)
                            GeovaultAuthManager.launchOAuthInBrowser(this, authorizeUrl)
                        },
                        onFailure = {
                            Toast.makeText(this, getString(R.string.error_server_unreachable), Toast.LENGTH_LONG).show()
                        }
                    )
                }
            }
        }

        disconnectButton.setOnClickListener {
            val dialog = AlertDialog.Builder(this)
                .setTitle(getString(R.string.disconnect_confirm_title))
                .setMessage(getString(R.string.disconnect_confirm_message))
                .setPositiveButton(getString(R.string.disconnect)) { _, _ ->
                    GeovaultAuthManager.revokeToken(this, GeovaultAuthManager.getAccessToken(this))
                    GeovaultAuthManager.revokeToken(this, GeovaultAuthManager.getRefreshToken(this))
                    AppResetFlow.execute(
                        context = this,
                        reason = AppResetFlow.Reason.MANUAL_SIGN_OUT,
                        mainActivityClass = MainActivity::class.java
                    )
                }
                .setNegativeButton(getString(R.string.cancel_button), null)
                .show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(
                ContextCompat.getColor(this, com.geovault.common.R.color.gv_common_dialog_positive_button)
            )
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(
                ContextCompat.getColor(this, com.geovault.common.R.color.gv_common_dialog_negative_button)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        updateConnectDisconnectVisibility()
    }


    override fun onDestroy() {
        super.onDestroy()
    }

    private fun updateConnectDisconnectVisibility() {
        val loggedIn = GeovaultAuthManager.isLoggedIn(this)
        serverUrlEdit.isEnabled = !loggedIn
        connectButton.visibility = if (loggedIn) View.GONE else View.VISIBLE
        disconnectButton.visibility = if (loggedIn) View.VISIBLE else View.GONE

        // Hide instructions when signed in
        settingsHelpText.visibility = if (loggedIn) View.GONE else View.VISIBLE
        
        if (loggedIn) {
            val email = GeovaultAuthManager.getCachedUserEmail(this)
            if (!email.isNullOrBlank()) {
                loggedInUserText.text = getString(com.geovault.common.R.string.gv_common_logged_in_as, email)
                loggedInUserText.visibility = View.VISIBLE
            } else {
                // If we don't have it yet (e.g. background fetch still running), reserve space
                loggedInUserText.text = "Logged in as..."
                loggedInUserText.visibility = View.INVISIBLE
                
                // Trigger a fetch if we are missing it
                GeovaultAuthManager.fetchUserStatus(this) { fetchedEmail ->
                    runOnUiThread {
                        if (!isDestroyed && GeovaultAuthManager.isLoggedIn(this) && !fetchedEmail.isNullOrBlank()) {
                            loggedInUserText.text = getString(com.geovault.common.R.string.gv_common_logged_in_as, fetchedEmail)
                            loggedInUserText.visibility = View.VISIBLE
                        }
                    }
                }
            }
        } else {
            loggedInUserText.visibility = View.GONE
        }
    }

    override fun finish() {
        super.finish()
        safeNoAnimation()
    }

    private fun safeNoAnimation() {
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
    }
}
