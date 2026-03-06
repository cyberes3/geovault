package com.geovault.tracker.fragments

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.geovault.common.GeovaultAuthManager
import com.geovault.tracker.MainActivity
import com.geovault.tracker.R
import com.google.android.material.button.MaterialButton

class SettingsFragment : Fragment() {

    private lateinit var serverUrlEdit: EditText
    private lateinit var connectButton: MaterialButton
    private lateinit var disconnectButton: MaterialButton
    private lateinit var loggedInUserText: TextView
    private lateinit var intervalEdit: EditText
    private lateinit var distanceEdit: EditText
    private lateinit var accuracyEdit: EditText
    private lateinit var extendedParamsSwitch: SwitchCompat
    private lateinit var significantMotionSwitch: SwitchCompat
    private lateinit var significantMotionRow: View
    private lateinit var startOnBootSwitch: SwitchCompat
    private lateinit var restartTrackingIfKilledSwitch: SwitchCompat
    private lateinit var startTrackingOnLaunchSwitch: SwitchCompat

    private fun normalizeServerUrl(url: String): String {
        var serverUrl = url.trim().trimStart('/').trimEnd('/')
        if (serverUrl.isNotEmpty() && !serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            serverUrl = "https://$serverUrl"
        }
        return serverUrl
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rootView = view.findViewById<View>(R.id.rootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomInset = if (ime.bottom > systemBars.bottom) ime.bottom else systemBars.bottom
            v.updatePadding(bottom = bottomInset)
            windowInsets
        }

        serverUrlEdit = view.findViewById(R.id.serverUrlEdit)
        connectButton = view.findViewById(R.id.connectButton)
        disconnectButton = view.findViewById(R.id.disconnectButton)
        loggedInUserText = view.findViewById(R.id.loggedInUserText)
        intervalEdit = view.findViewById(R.id.intervalEdit)
        distanceEdit = view.findViewById(R.id.distanceEdit)
        accuracyEdit = view.findViewById(R.id.accuracyEdit)
        extendedParamsSwitch = view.findViewById(R.id.extendedParamsSwitch)
        significantMotionSwitch = view.findViewById(R.id.significantMotionSwitch)
        significantMotionRow = view.findViewById(R.id.significantMotionRow)
        startOnBootSwitch = view.findViewById(R.id.startOnBootSwitch)
        restartTrackingIfKilledSwitch = view.findViewById(R.id.restartTrackingIfKilledSwitch)
        startTrackingOnLaunchSwitch = view.findViewById(R.id.startTrackingOnLaunchSwitch)

        loadSettings()
        applyMotionSensorAvailability()
        updateUi()

        connectButton.setOnClickListener { onConnectClicked() }
        disconnectButton.setOnClickListener { onDisconnectClicked() }

        extendedParamsSwitch.setOnCheckedChangeListener { _, isChecked ->
            saveSetting("extended_params", isChecked)
        }

        significantMotionSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (significantMotionSwitch.isEnabled) {
                saveSetting("significant_motion_only", isChecked)
            }
        }

        startOnBootSwitch.setOnCheckedChangeListener { _, isChecked ->
            saveSetting("start_on_boot", isChecked)
        }

        restartTrackingIfKilledSwitch.setOnCheckedChangeListener { _, isChecked ->
            saveSetting("restart_tracking_if_killed", isChecked)
        }

        startTrackingOnLaunchSwitch.setOnCheckedChangeListener { _, isChecked ->
            saveSetting("start_tracking_on_launch", isChecked)
        }

        view.findViewById<View>(R.id.loggingHelpButton).setOnClickListener { showLoggingHelpDialog() }
    }

    private fun showLoggingHelpDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.logging_help_title))
            .setMessage(getString(R.string.logging_help_message))
            .setPositiveButton(getString(R.string.close), null)
            .show()
    }

    private fun applyMotionSensorAvailability() {
        val sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val motionSensorAvailable = sensorManager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) != null

        if (!motionSensorAvailable) {
            significantMotionSwitch.isChecked = false
            significantMotionSwitch.isEnabled = false
            saveSetting("significant_motion_only", false)
            significantMotionRow.setOnClickListener {
                Toast.makeText(requireContext(), getString(R.string.motion_sensor_unavailable_toast), Toast.LENGTH_SHORT).show()
            }
        } else {
            significantMotionSwitch.isEnabled = true
            significantMotionRow.setOnClickListener(null)
        }
    }

    private fun loadSettings() {
        val prefs = requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
        val serverUrl = GeovaultAuthManager.getServerUrl(requireContext())
        if (serverUrl.isNotEmpty()) {
            serverUrlEdit.setText(serverUrl)
        } else {
            val otherUrls = com.geovault.common.ServerUrlContract.getServerUrlsFromOtherApps(requireContext())
            if (otherUrls.size == 1) {
                serverUrlEdit.setText(otherUrls.single())
            }
        }
        intervalEdit.setText(prefs.getString("logging_interval", "15"))
        distanceEdit.setText(prefs.getString("logging_distance", "10"))
        accuracyEdit.setText(prefs.getString("logging_accuracy", "50"))
        extendedParamsSwitch.isChecked = prefs.getBoolean("extended_params", true)
        significantMotionSwitch.isChecked = prefs.getBoolean("significant_motion_only", true)
        startOnBootSwitch.isChecked = prefs.getBoolean("start_on_boot", false)
        restartTrackingIfKilledSwitch.isChecked = prefs.getBoolean("restart_tracking_if_killed", true)
        startTrackingOnLaunchSwitch.isChecked = prefs.getBoolean("start_tracking_on_launch", false)

        intervalEdit.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                saveSetting("logging_interval", s?.toString() ?: "15")
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        distanceEdit.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                saveSetting("logging_distance", s?.toString() ?: "10")
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        accuracyEdit.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                saveSetting("logging_accuracy", s?.toString() ?: "50")
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun saveSetting(key: String, value: String) {
        requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE).edit().putString(key, value).apply()
    }

    private fun saveSetting(key: String, value: Boolean) {
        requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE).edit().putBoolean(key, value).apply()
    }

    private fun updateUi() {
        val isLoggedIn = GeovaultAuthManager.isLoggedIn(requireContext())
        connectButton.visibility = if (isLoggedIn) View.GONE else View.VISIBLE
        disconnectButton.visibility = if (isLoggedIn) View.VISIBLE else View.GONE
        loggedInUserText.visibility = if (isLoggedIn) View.VISIBLE else View.GONE

        val email = GeovaultAuthManager.getCachedUserEmail(requireContext())
        loggedInUserText.text = if (email != null) "Logged in as $email" else "Logged in"
    }

    private fun onConnectClicked() {
        val url = normalizeServerUrl(serverUrlEdit.text.toString())
        if (url.isEmpty()) {
            (requireActivity() as? MainActivity)?.showSnackbar("Please enter server URL")
            return
        }
        GeovaultAuthManager.setServerUrl(requireContext(), url)
        
        val (verifier, challenge) = GeovaultAuthManager.generatePkcePair()
        val state = java.util.UUID.randomUUID().toString()
        GeovaultAuthManager.savePkceState(requireContext(), verifier, state)
        
        val authUrl = GeovaultAuthManager.buildAuthorizeUrl(url, challenge, state)
        GeovaultAuthManager.launchOAuthInBrowser(requireContext(), authUrl)
    }

    private fun onDisconnectClicked() {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.disconnect_confirm_title))
            .setMessage(getString(R.string.disconnect_confirm_message))
            .setPositiveButton(getString(R.string.disconnect)) { _, _ ->
                GeovaultAuthManager.revokeToken(requireContext(), GeovaultAuthManager.getAccessToken(requireContext()))
                GeovaultAuthManager.revokeToken(requireContext(), GeovaultAuthManager.getRefreshToken(requireContext()))
                GeovaultAuthManager.clearTokens(requireContext())
                updateUi()
                Toast.makeText(requireContext(), getString(R.string.disconnect), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel_button), null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.error_red)
        )
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(
            ContextCompat.getColor(requireContext(), com.geovault.common.R.color.gv_common_dialog_negative_button)
        )
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onDestroyView() {
        super.onDestroyView()
    }
}
