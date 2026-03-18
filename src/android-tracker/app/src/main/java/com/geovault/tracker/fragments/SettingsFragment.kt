package com.geovault.tracker.fragments

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.widget.NestedScrollView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.geovault.common.AppResetFlow
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.KeyboardScrollHelper
import com.geovault.common.R as CommonR
import com.geovault.tracker.navigation.navHost
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
    private lateinit var profileSpinner: AutoCompleteTextView
    private lateinit var extendedParamsSwitch: SwitchCompat
    private lateinit var significantMotionSwitch: SwitchCompat
    private lateinit var significantMotionRow: View
    private lateinit var startOnBootSwitch: SwitchCompat
    private lateinit var restartTrackingIfKilledSwitch: SwitchCompat
    private lateinit var startTrackingOnLaunchSwitch: SwitchCompat
    private lateinit var autoTrackingSwitch: SwitchCompat
    private lateinit var hiddenTrackersButton: MaterialButton
    private lateinit var viewAllTrackersButton: MaterialButton
    private lateinit var distanceLabel: TextView
    private lateinit var accuracyLabel: TextView
    private lateinit var settingsScrollView: NestedScrollView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        serverUrlEdit = view.findViewById(R.id.serverUrlEdit)
        connectButton = view.findViewById(R.id.connectButton)
        disconnectButton = view.findViewById(R.id.disconnectButton)
        loggedInUserText = view.findViewById(R.id.loggedInUserText)
        intervalEdit = view.findViewById(R.id.intervalEdit)
        distanceEdit = view.findViewById(R.id.distanceEdit)
        accuracyEdit = view.findViewById(R.id.accuracyEdit)
        profileSpinner = view.findViewById(R.id.profileSpinner)
        extendedParamsSwitch = view.findViewById(R.id.extendedParamsSwitch)
        significantMotionSwitch = view.findViewById(R.id.significantMotionSwitch)
        significantMotionRow = view.findViewById(R.id.significantMotionRow)
        startOnBootSwitch = view.findViewById(R.id.startOnBootSwitch)
        restartTrackingIfKilledSwitch = view.findViewById(R.id.restartTrackingIfKilledSwitch)
        startTrackingOnLaunchSwitch = view.findViewById(R.id.startTrackingOnLaunchSwitch)
        autoTrackingSwitch = view.findViewById(R.id.autoTrackingSwitch)
        hiddenTrackersButton = view.findViewById(R.id.hiddenTrackersButton)
        viewAllTrackersButton = view.findViewById(R.id.viewAllTrackersButton)
        distanceLabel = view.findViewById(R.id.distanceLabel)
        accuracyLabel = view.findViewById(R.id.accuracyLabel)
        settingsScrollView = view.findViewById(R.id.settingsScrollView)

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

        autoTrackingSwitch.setOnCheckedChangeListener { _, isChecked ->
            saveSetting("auto_tracking_enabled", isChecked)
            updateAutoTrackingUi(isChecked)
        }
        hiddenTrackersButton.setOnClickListener { navHost()?.showHiddenTrackersFragment() }
        viewAllTrackersButton.setOnClickListener { navHost()?.openMapAllTrackers() }

        view.findViewById<View>(R.id.loggingHelpButton).setOnClickListener { showLoggingHelpDialog() }
        setupKeyboardAwareScrolling()
        setupProfileSpinner()
    }

    private fun setupKeyboardAwareScrolling() {
        KeyboardScrollHelper.installNestedScrollFocusAutoScroll(
            scrollView = settingsScrollView,
            focusableViews = listOf(intervalEdit, distanceEdit, accuracyEdit, serverUrlEdit),
            centerBias = 0.5f
        )
    }

    private fun toDisplay(meters: Float, isImperial: Boolean): Int {
        return if (isImperial) (meters * 3.28084f).toInt() else meters.toInt()
    }

    private fun fromDisplay(displayVal: Float, isImperial: Boolean): Float {
        return if (isImperial) displayVal / 3.28084f else displayVal
    }

    private fun updateUnitLabels(isImperial: Boolean) {
        val unit = getString(if (isImperial) R.string.unit_ft else R.string.unit_m)
        distanceLabel.text = getString(R.string.distance_filter_label, unit)
        accuracyLabel.text = getString(R.string.accuracy_filter_label, unit)
    }

    private var isUpdatingFromSpinner = false

    /** Selected profile index (0=walking, 1=biking, 2=driving, 3=custom). */
    private var selectedProfileIndex = 1

    private val profiles = listOf(
        Triple("walking", "30", Pair("10", "50")),
        Triple("biking", "15", Pair("30", "100")),
        Triple("driving", "10", Pair("100", "200")),
        Triple("custom", "", Pair("", ""))
    )

    private fun setupProfileSpinner() {
        val labels = listOf(
            getString(R.string.profile_walking),
            getString(R.string.profile_biking),
            getString(R.string.profile_driving),
            getString(R.string.profile_custom)
        )
        val adapter = ArrayAdapter(requireContext(), CommonR.layout.gv_common_item_dropdown, labels)
        profileSpinner.setAdapter(adapter)

        profileSpinner.setOnItemClickListener { _, _, position, _ ->
            selectedProfileIndex = position
            if (position < 3) {
                val profile = profiles[position]
                isUpdatingFromSpinner = true
                intervalEdit.setText(profile.second)
                distanceEdit.setText(profile.third.first)
                accuracyEdit.setText(profile.third.second)
                isUpdatingFromSpinner = false
                saveSetting("tracking_profile", position.toString())
            } else if (position == 3) {
                saveSetting("tracking_profile", "3")
            }
        }

        // Load saved profile
        val prefs = requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
        val savedProfile = prefs.getString("tracking_profile", "1")?.toIntOrNull() ?: 1
        selectedProfileIndex = savedProfile
        profileSpinner.setText(labels[savedProfile], false)
    }

    private fun updateAutoTrackingUi(enabled: Boolean) {
        val alpha = if (enabled) 0.5f else 1.0f
        profileSpinner.isEnabled = !enabled
        profileSpinner.isClickable = !enabled
        profileSpinner.isFocusable = !enabled
        profileSpinner.alpha = alpha
        intervalEdit.isEnabled = !enabled
        intervalEdit.alpha = alpha
        distanceEdit.isEnabled = !enabled
        distanceEdit.alpha = alpha
    }

    private fun updateProfileToCustom() {
        if (isUpdatingFromSpinner) return
        if (selectedProfileIndex != 3) {
            selectedProfileIndex = 3
            profileSpinner.setText(getString(R.string.profile_custom), false)
        }
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
        
        val isImperial = com.geovault.common.UnitUtils.usesImperialUnitsDefault(requireContext())
        updateUnitLabels(isImperial)
        
        val interval = prefs.getString("logging_interval", "15") ?: "15"
        val distance = prefs.getString("logging_distance", "10")?.toFloatOrNull() ?: 10f
        val accuracy = prefs.getString("logging_accuracy", "50")?.toFloatOrNull() ?: 50f
        
        intervalEdit.setText(interval)
        distanceEdit.setText(toDisplay(distance, isImperial).toString())
        accuracyEdit.setText(toDisplay(accuracy, isImperial).toString())
        
        autoTrackingSwitch.isChecked = prefs.getBoolean("auto_tracking_enabled", false)
        updateAutoTrackingUi(autoTrackingSwitch.isChecked)
        
        extendedParamsSwitch.isChecked = prefs.getBoolean("extended_params", true)
        significantMotionSwitch.isChecked = prefs.getBoolean("significant_motion_only", true)
        startOnBootSwitch.isChecked = prefs.getBoolean("start_on_boot", false)
        restartTrackingIfKilledSwitch.isChecked = prefs.getBoolean("restart_tracking_if_killed", true)
        startTrackingOnLaunchSwitch.isChecked = prefs.getBoolean("start_tracking_on_launch", false)

        intervalEdit.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val value = s?.toString() ?: "15"
                saveSetting("logging_interval", value)
                updateProfileToCustom()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        distanceEdit.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val isImperial = com.geovault.common.UnitUtils.usesImperialUnitsDefault(requireContext())
                val displayValue = s?.toString()?.toFloatOrNull() ?: (if (isImperial) 33f else 10f)
                val metersValue = fromDisplay(displayValue, isImperial)
                saveSetting("logging_distance", metersValue.toString())
                updateProfileToCustom()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        accuracyEdit.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val isImperial = com.geovault.common.UnitUtils.usesImperialUnitsDefault(requireContext())
                val displayValue = s?.toString()?.toFloatOrNull() ?: (if (isImperial) 164f else 50f)
                val metersValue = fromDisplay(displayValue, isImperial)
                saveSetting("logging_accuracy", metersValue.toString())
                updateProfileToCustom()
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
        serverUrlEdit.isEnabled = !isLoggedIn
        connectButton.visibility = if (isLoggedIn) View.GONE else View.VISIBLE
        disconnectButton.visibility = if (isLoggedIn) View.VISIBLE else View.GONE
        loggedInUserText.visibility = if (isLoggedIn) View.VISIBLE else View.GONE

        val email = GeovaultAuthManager.getCachedUserEmail(requireContext())
        loggedInUserText.text = if (email != null) getString(R.string.logged_in_as, email) else getString(R.string.logged_in)
    }

    private fun onConnectClicked() {
        val url = GeovaultAuthManager.normalizeServerUrl(serverUrlEdit.text.toString())
        if (url.isEmpty()) {
            navHost()?.showSnackbar(getString(R.string.error_enter_server_url))
            return
        }
        connectButton.isEnabled = false
        navHost()?.showSnackbar(getString(R.string.connecting_server))
        GeovaultAuthManager.resolveServerUrlToCanonical(url) { result ->
            requireActivity().runOnUiThread {
                connectButton.isEnabled = true
                result.fold(
                    onSuccess = { resolvedUrl ->
                        GeovaultAuthManager.setServerUrl(requireContext(), resolvedUrl)
                        val (verifier, challenge) = GeovaultAuthManager.generatePkcePair()
                        val state = java.util.UUID.randomUUID().toString()
                        GeovaultAuthManager.savePkceState(requireContext(), verifier, state)
                        val authUrl = GeovaultAuthManager.buildAuthorizeUrl(resolvedUrl, challenge, state)
                        GeovaultAuthManager.launchOAuthInBrowser(requireContext(), authUrl)
                    },
                    onFailure = {
                        navHost()?.showSnackbar(getString(R.string.error_server_unreachable))
                    }
                )
            }
        }
    }

    private fun onDisconnectClicked() {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.disconnect_confirm_title))
            .setMessage(getString(R.string.disconnect_confirm_message))
            .setPositiveButton(getString(R.string.disconnect)) { _, _ ->
                GeovaultAuthManager.revokeToken(requireContext(), GeovaultAuthManager.getAccessToken(requireContext()))
                GeovaultAuthManager.revokeToken(requireContext(), GeovaultAuthManager.getRefreshToken(requireContext()))
                AppResetFlow.execute(
                    context = requireContext(),
                    reason = AppResetFlow.Reason.MANUAL_SIGN_OUT,
                    mainActivityClass = com.geovault.tracker.MainActivity::class.java
                )
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
