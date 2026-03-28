package com.geovault.tracker.fragments

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.geovault.common.AppResetFlow
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.KeyboardScrollHelper
import com.geovault.common.ToggleHelpCardView
import com.geovault.common.R as CommonR
import com.geovault.tracker.navigation.navHost
import com.geovault.tracker.R
import com.geovault.tracker.settings.TrackerSettings
import com.geovault.tracker.settings.TrackerTrackingProfile
import com.geovault.tracker.ui.applyDialogButtonColors
import com.google.android.material.button.MaterialButton
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@AndroidEntryPoint
class SettingsFragment : Fragment() {
    private val viewModel: SettingsViewModel by viewModels()

    private lateinit var serverUrlEdit: EditText
    private lateinit var connectButton: MaterialButton
    private lateinit var disconnectButton: MaterialButton
    private lateinit var loggedInUserText: TextView
    private lateinit var intervalEdit: EditText
    private lateinit var distanceEdit: EditText
    private lateinit var accuracyEdit: EditText
    private lateinit var lowAccuracyFallbackTimeoutEdit: EditText
    private lateinit var profileSpinner: AutoCompleteTextView
    private lateinit var extendedParamsSwitch: ToggleHelpCardView
    private lateinit var significantMotionSwitch: ToggleHelpCardView
    private lateinit var significantMotionRow: View
    private lateinit var startOnBootSwitch: ToggleHelpCardView
    private lateinit var startTrackingOnLaunchSwitch: ToggleHelpCardView
    private lateinit var keepScreenOnWhileViewingMapSwitch: ToggleHelpCardView
    private lateinit var autoTrackingSwitch: ToggleHelpCardView
    private lateinit var lowAccuracyFallbackSwitch: ToggleHelpCardView
    private lateinit var hiddenTrackersButton: MaterialButton
    private lateinit var viewAllTrackersButton: MaterialButton
    private lateinit var distanceLabel: TextView
    private lateinit var accuracyLabel: TextView
    private lateinit var settingsScrollView: NestedScrollView
    private var isBindingSettings = true
    private var hasHydratedSettings = false
    private var lastRenderedSettings: TrackerSettings? = null

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
        lowAccuracyFallbackTimeoutEdit = view.findViewById(R.id.lowAccuracyFallbackTimeoutEdit)
        profileSpinner = view.findViewById(R.id.profileSpinner)
        extendedParamsSwitch = view.findViewById(R.id.extendedParamsSwitch)
        significantMotionRow = view.findViewById(R.id.significantMotionRow)
        significantMotionSwitch = view.findViewById(R.id.significantMotionRow)
        startOnBootSwitch = view.findViewById(R.id.startOnBootSwitch)
        startTrackingOnLaunchSwitch = view.findViewById(R.id.startTrackingOnLaunchSwitch)
        keepScreenOnWhileViewingMapSwitch = view.findViewById(R.id.keepScreenOnWhileViewingMapSwitch)
        autoTrackingSwitch = view.findViewById(R.id.autoTrackingSwitch)
        lowAccuracyFallbackSwitch = view.findViewById(R.id.lowAccuracyFallbackSwitch)
        hiddenTrackersButton = view.findViewById(R.id.hiddenTrackersButton)
        viewAllTrackersButton = view.findViewById(R.id.viewAllTrackersButton)
        distanceLabel = view.findViewById(R.id.distanceLabel)
        accuracyLabel = view.findViewById(R.id.accuracyLabel)
        settingsScrollView = view.findViewById(R.id.settingsScrollView)

        setupProfileSpinner()
        setupSettingsListeners()
        applyMotionSensorAvailability()
        bindSettingsState()
        updateUi()

        connectButton.setOnClickListener { onConnectClicked() }
        disconnectButton.setOnClickListener { onDisconnectClicked() }
        hiddenTrackersButton.setOnClickListener { navHost()?.showHiddenTrackersFragment() }
        viewAllTrackersButton.setOnClickListener { navHost()?.openMapAllTrackers() }
        view.findViewById<View>(R.id.loggingHelpButton).setOnClickListener { showLoggingHelpDialog() }
        setupKeyboardAwareScrolling()
    }

    private fun setupKeyboardAwareScrolling() {
        KeyboardScrollHelper.installNestedScrollFocusAutoScroll(
            scrollView = settingsScrollView,
            focusableViews = listOf(
                intervalEdit,
                distanceEdit,
                accuracyEdit,
                lowAccuracyFallbackTimeoutEdit,
                serverUrlEdit
            ),
            centerBias = 0.5f
        )
    }

    private fun toDisplay(meters: Float, isImperial: Boolean): Int {
        val converted = if (isImperial) meters * 3.28084f else meters
        if (converted <= 0f) return 0
        // Preserve user intent for small positive values in integer-only inputs.
        return converted.roundToInt().coerceAtLeast(1)
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
            if (isBindingSettings) return@setOnItemClickListener
            selectedProfileIndex = position
            if (position < 3) {
                val profile = profiles[position]
                isUpdatingFromSpinner = true
                intervalEdit.setText(profile.second)
                distanceEdit.setText(profile.third.first)
                accuracyEdit.setText(profile.third.second)
                isUpdatingFromSpinner = false
                viewModel.setTrackingProfile(TrackerTrackingProfile.fromIndex(position))
            } else if (position == 3) {
                viewModel.setTrackingProfile(TrackerTrackingProfile.CUSTOM)
            }
        }
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
        accuracyEdit.isEnabled = !enabled
        accuracyEdit.alpha = alpha
    }

    private fun updateProfileToCustom() {
        if (isUpdatingFromSpinner) return
        if (selectedProfileIndex != 3) {
            selectedProfileIndex = 3
            profileSpinner.setText(getString(R.string.profile_custom), false)
            viewModel.setTrackingProfile(TrackerTrackingProfile.CUSTOM)
        }
    }

    private fun updateLowAccuracyFallbackUi(enabled: Boolean) {
        lowAccuracyFallbackTimeoutEdit.isEnabled = enabled
        lowAccuracyFallbackTimeoutEdit.alpha = if (enabled) 1f else 0.5f
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
            viewModel.setSignificantDataOnly(false)
            significantMotionRow.setOnClickListener {
                Toast.makeText(requireContext(), getString(R.string.motion_sensor_unavailable_toast), Toast.LENGTH_SHORT).show()
            }
        } else {
            significantMotionSwitch.isEnabled = true
            significantMotionRow.setOnClickListener(null)
        }
    }

    private fun bindSettingsState() {
        loadServerUrlField()
        viewModel.dumpDebugState("settings_fragment_bind_start")
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { uiState ->
                if (uiState.phase != SettingsPhase.Ready) {
                    Log.d(TAG, "ui_state_not_ready phase=${uiState.phase}")
                    return@collect
                }
                if (lastRenderedSettings != uiState.settings) {
                    Log.i(TAG, "ui_apply_settings settings=${
                        summary(uiState.settings)
                    }")
                    applySettingsToUi(uiState.settings)
                    lastRenderedSettings = uiState.settings
                }
            }
        }
    }

    private fun loadServerUrlField() {
        val serverUrl = GeovaultAuthManager.getServerUrl(requireContext())
        if (serverUrl.isNotEmpty()) {
            serverUrlEdit.setText(serverUrl)
        } else {
            val otherUrls = com.geovault.common.ServerUrlContract.getServerUrlsFromOtherApps(requireContext())
            if (otherUrls.size == 1) {
                serverUrlEdit.setText(otherUrls.single())
            }
        }
    }

    private fun applySettingsToUi(settings: TrackerSettings) {
        isBindingSettings = true
        val isImperial = com.geovault.common.UnitUtils.usesImperialUnitsDefault(requireContext())
        updateUnitLabels(isImperial)

        setSwitchCheckedIfChanged(autoTrackingSwitch, settings.autoTrackingMode)
        updateAutoTrackingUi(autoTrackingSwitch.isChecked)

        if (settings.autoTrackingMode) {
            setSpinnerTextIfChanged(profileSpinner, getString(R.string.profile_auto))
            updateNumericEditFromState(intervalEdit, "")
            updateNumericEditFromState(distanceEdit, "")
            updateNumericEditFromState(accuracyEdit, "")
        } else {
            updateNumericEditFromState(intervalEdit, settings.loggingIntervalSec.toString())
            updateNumericEditFromState(distanceEdit, toDisplay(settings.distanceFilterMeters, isImperial).toString())
            updateNumericEditFromState(accuracyEdit, toDisplay(settings.accuracyFilterMeters, isImperial).toString())
            selectedProfileIndex = settings.trackingProfile.index
            val labels = listOf(
                getString(R.string.profile_walking),
                getString(R.string.profile_biking),
                getString(R.string.profile_driving),
                getString(R.string.profile_custom)
            )
            setSpinnerTextIfChanged(profileSpinner, labels[selectedProfileIndex])
        }

        setSwitchCheckedIfChanged(extendedParamsSwitch, settings.sendExtendedData)
        setSwitchCheckedIfChanged(significantMotionSwitch, settings.significantDataOnly)
        setSwitchCheckedIfChanged(lowAccuracyFallbackSwitch, settings.lowAccuracyFallbackEnabled)
        updateLowAccuracyFallbackUi(settings.lowAccuracyFallbackEnabled)
        updateNumericEditFromState(
            lowAccuracyFallbackTimeoutEdit,
            settings.lowAccuracyFallbackTimeoutSec.toString()
        )
        setSwitchCheckedIfChanged(startOnBootSwitch, settings.startOnBoot)
        setSwitchCheckedIfChanged(startTrackingOnLaunchSwitch, settings.startTrackingOnLaunch)
        setSwitchCheckedIfChanged(keepScreenOnWhileViewingMapSwitch, settings.keepScreenOnWhileViewingMap)

        hasHydratedSettings = true
        isBindingSettings = false
    }

    private fun updateNumericEditFromState(editText: EditText, value: String) {
        // Avoid stomping active typing, which causes caret jumps.
        if (editText.hasFocus()) return
        if (editText.text?.toString() == value) return
        editText.setText(value)
    }

    private fun setSpinnerTextIfChanged(spinner: AutoCompleteTextView, value: String) {
        if (spinner.hasFocus()) return
        if ((spinner.text?.toString() ?: "") == value) return
        spinner.setText(value, false)
    }

    private fun setSwitchCheckedIfChanged(toggle: ToggleHelpCardView, value: Boolean) {
        if (toggle.isChecked == value) return
        toggle.isChecked = value
    }

    private fun setupSettingsListeners() {
        extendedParamsSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (shouldIgnoreSettingChange()) return@setOnCheckedChangeListener
            Log.i(TAG, "user_toggle key=extendedParams value=$isChecked")
            viewModel.setSendExtendedData(isChecked)
        }

        significantMotionSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (shouldIgnoreSettingChange()) return@setOnCheckedChangeListener
            if (significantMotionSwitch.isEnabled) {
                Log.i(TAG, "user_toggle key=significantMotion value=$isChecked")
                viewModel.setSignificantDataOnly(isChecked)
            }
        }

        startOnBootSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (shouldIgnoreSettingChange()) return@setOnCheckedChangeListener
            Log.i(TAG, "user_toggle key=startOnBoot value=$isChecked")
            viewModel.setStartOnBoot(isChecked)
        }

        startTrackingOnLaunchSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (shouldIgnoreSettingChange()) return@setOnCheckedChangeListener
            Log.i(TAG, "user_toggle key=startTrackingOnLaunch value=$isChecked")
            viewModel.setStartTrackingOnLaunch(isChecked)
        }

        keepScreenOnWhileViewingMapSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (shouldIgnoreSettingChange()) return@setOnCheckedChangeListener
            Log.i(TAG, "user_toggle key=keepScreenOnWhileViewingMap value=$isChecked")
            viewModel.setKeepScreenOnWhileViewingMap(isChecked)
        }

        autoTrackingSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (shouldIgnoreSettingChange()) return@setOnCheckedChangeListener
            Log.i(TAG, "user_toggle key=autoTracking value=$isChecked")
            viewModel.setAutoTrackingMode(isChecked)
            updateAutoTrackingUi(isChecked)
        }

        lowAccuracyFallbackSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (shouldIgnoreSettingChange()) return@setOnCheckedChangeListener
            Log.i(TAG, "user_toggle key=lowAccuracyFallback value=$isChecked")
            viewModel.setLowAccuracyFallbackEnabled(isChecked)
            updateLowAccuracyFallbackUi(isChecked)
        }

        configureNumericInputCommit(intervalEdit) { commitIntervalInput() }
        configureNumericInputCommit(distanceEdit) { commitDistanceInput() }
        configureNumericInputCommit(accuracyEdit) { commitAccuracyInput() }
        configureNumericInputCommit(lowAccuracyFallbackTimeoutEdit) { commitLowAccuracyFallbackTimeoutInput() }
        setupProfileCustomWatchers()
    }

    private fun setupProfileCustomWatchers() {
        intervalEdit.doAfterTextChanged {
            markCustomIfManualIntervalChanged()
        }
        distanceEdit.doAfterTextChanged {
            markCustomIfManualDistanceChanged()
        }
        accuracyEdit.doAfterTextChanged {
            markCustomIfManualAccuracyChanged()
        }
    }

    private fun shouldIgnoreManualProfileWatcher(): Boolean {
        return isBindingSettings || isUpdatingFromSpinner
    }

    private fun markCustomIfManualIntervalChanged() {
        if (shouldIgnoreManualProfileWatcher()) return
        val raw = intervalEdit.text?.toString()?.trim().orEmpty()
        val parsed = raw.toLongOrNull() ?: run {
            updateProfileToCustom()
            return
        }
        val clamped = TrackerSettings.clampLoggingIntervalSec(parsed)
        val previous = viewModel.uiState.value.settings.loggingIntervalSec
        if (clamped != previous) {
            updateProfileToCustom()
        }
    }

    private fun markCustomIfManualDistanceChanged() {
        if (shouldIgnoreManualProfileWatcher()) return
        val raw = distanceEdit.text?.toString()?.trim().orEmpty()
        val parsed = raw.toFloatOrNull() ?: run {
            updateProfileToCustom()
            return
        }
        val isImperial = com.geovault.common.UnitUtils.usesImperialUnitsDefault(requireContext())
        val converted = fromDisplay(parsed, isImperial)
        val clamped = TrackerSettings.clampDistanceFilterMeters(converted)
        val previous = viewModel.uiState.value.settings.distanceFilterMeters
        if ((clamped - previous).let { kotlin.math.abs(it) } > 0.0001f) {
            updateProfileToCustom()
        }
    }

    private fun markCustomIfManualAccuracyChanged() {
        if (shouldIgnoreManualProfileWatcher()) return
        val raw = accuracyEdit.text?.toString()?.trim().orEmpty()
        val parsed = raw.toFloatOrNull() ?: run {
            updateProfileToCustom()
            return
        }
        val isImperial = com.geovault.common.UnitUtils.usesImperialUnitsDefault(requireContext())
        val converted = fromDisplay(parsed, isImperial)
        val clamped = TrackerSettings.clampAccuracyFilterMeters(converted)
        val previous = viewModel.uiState.value.settings.accuracyFilterMeters
        if ((clamped - previous).let { kotlin.math.abs(it) } > 0.0001f) {
            updateProfileToCustom()
        }
    }

    private fun configureNumericInputCommit(editText: EditText, onCommit: () -> Unit) {
        editText.setOnFocusChangeListener { _, hasFocus ->
            if (isBindingSettings) return@setOnFocusChangeListener
            if (!hasFocus) onCommit()
        }
        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onCommit()
                editText.clearFocus()
                true
            } else {
                false
            }
        }
    }

    private fun commitIntervalInput() {
        val raw = intervalEdit.text?.toString()?.trim().orEmpty()
        val parsed = raw.toLongOrNull()
        if (parsed == null) {
            updateNumericEditFromState(
                intervalEdit,
                viewModel.uiState.value.settings.loggingIntervalSec.toString()
            )
            return
        }
        val clamped = TrackerSettings.clampLoggingIntervalSec(parsed)
        val previous = viewModel.uiState.value.settings.loggingIntervalSec
        viewModel.setLoggingIntervalSec(parsed)
        if (clamped != previous) {
            updateProfileToCustom()
        }
    }

    private fun commitDistanceInput() {
        val raw = distanceEdit.text?.toString()?.trim().orEmpty()
        val parsed = raw.toFloatOrNull()
        val isImperial = com.geovault.common.UnitUtils.usesImperialUnitsDefault(requireContext())
        if (parsed == null) {
            val fallback = toDisplay(viewModel.uiState.value.settings.distanceFilterMeters, isImperial).toString()
            updateNumericEditFromState(distanceEdit, fallback)
            return
        }
        val converted = fromDisplay(parsed, isImperial)
        val clamped = TrackerSettings.clampDistanceFilterMeters(converted)
        val previous = viewModel.uiState.value.settings.distanceFilterMeters
        viewModel.setDistanceFilterMeters(converted)
        if ((clamped - previous).let { kotlin.math.abs(it) } > 0.0001f) {
            updateProfileToCustom()
        }
    }

    private fun commitAccuracyInput() {
        val raw = accuracyEdit.text?.toString()?.trim().orEmpty()
        val parsed = raw.toFloatOrNull()
        val isImperial = com.geovault.common.UnitUtils.usesImperialUnitsDefault(requireContext())
        if (parsed == null) {
            val fallback = toDisplay(viewModel.uiState.value.settings.accuracyFilterMeters, isImperial).toString()
            updateNumericEditFromState(accuracyEdit, fallback)
            return
        }
        val converted = fromDisplay(parsed, isImperial)
        val clamped = TrackerSettings.clampAccuracyFilterMeters(converted)
        val previous = viewModel.uiState.value.settings.accuracyFilterMeters
        viewModel.setAccuracyFilterMeters(converted)
        if ((clamped - previous).let { kotlin.math.abs(it) } > 0.0001f) {
            updateProfileToCustom()
        }
    }

    private fun commitLowAccuracyFallbackTimeoutInput() {
        val raw = lowAccuracyFallbackTimeoutEdit.text?.toString()?.trim().orEmpty()
        val parsed = raw.toLongOrNull()
        if (parsed == null) {
            updateNumericEditFromState(
                lowAccuracyFallbackTimeoutEdit,
                viewModel.uiState.value.settings.lowAccuracyFallbackTimeoutSec.toString()
            )
            return
        }
        viewModel.setLowAccuracyFallbackTimeoutSec(parsed)
    }

    private fun applyDefaultsForInvalidInputs() {
        if (isBindingSettings) return
        val isImperial = com.geovault.common.UnitUtils.usesImperialUnitsDefault(requireContext())

        val intervalParsed = intervalEdit.text?.toString()?.trim()?.toLongOrNull()
        if (intervalParsed == null) {
            val defaultInterval = TrackerSettings.DEFAULT_LOGGING_INTERVAL_SEC
            viewModel.setLoggingIntervalSec(defaultInterval)
            updateNumericEditFromState(intervalEdit, defaultInterval.toString())
        } else {
            viewModel.setLoggingIntervalSec(intervalParsed)
        }

        val distanceParsed = distanceEdit.text?.toString()?.trim()?.toFloatOrNull()
        if (distanceParsed == null) {
            val defaultMeters = TrackerSettings.DEFAULT_DISTANCE_FILTER_METERS
            viewModel.setDistanceFilterMeters(defaultMeters)
            updateNumericEditFromState(distanceEdit, toDisplay(defaultMeters, isImperial).toString())
        } else {
            viewModel.setDistanceFilterMeters(fromDisplay(distanceParsed, isImperial))
        }

        val accuracyParsed = accuracyEdit.text?.toString()?.trim()?.toFloatOrNull()
        if (accuracyParsed == null) {
            val defaultMeters = TrackerSettings.DEFAULT_ACCURACY_FILTER_METERS
            viewModel.setAccuracyFilterMeters(defaultMeters)
            updateNumericEditFromState(accuracyEdit, toDisplay(defaultMeters, isImperial).toString())
        } else {
            viewModel.setAccuracyFilterMeters(fromDisplay(accuracyParsed, isImperial))
        }

        val fallbackTimeoutParsed = lowAccuracyFallbackTimeoutEdit.text?.toString()?.trim()?.toLongOrNull()
        if (fallbackTimeoutParsed == null) {
            val defaultTimeoutSec = TrackerSettings.DEFAULT_LOW_ACCURACY_FALLBACK_TIMEOUT_SEC
            viewModel.setLowAccuracyFallbackTimeoutSec(defaultTimeoutSec)
            updateNumericEditFromState(lowAccuracyFallbackTimeoutEdit, defaultTimeoutSec.toString())
        } else {
            viewModel.setLowAccuracyFallbackTimeoutSec(fallbackTimeoutParsed)
        }
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
        dialog.applyDialogButtonColors(requireContext(), destructiveAction = true)
    }

    override fun onResume() {
        super.onResume()
        viewModel.dumpDebugState("settings_fragment_onResume")
    }

    override fun onPause() {
        // When settings view loses focus, normalize invalid logging inputs to defaults.
        applyDefaultsForInvalidInputs()
        viewModel.dumpDebugState("settings_fragment_onPause")
        super.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
    }

    private fun summary(settings: TrackerSettings): String {
        return "auto=${settings.autoTrackingMode},startOnBoot=${settings.startOnBoot},startOnLaunch=${settings.startTrackingOnLaunch},extended=${settings.sendExtendedData},sigMotion=${settings.significantDataOnly},lowAccFallback=${settings.lowAccuracyFallbackEnabled},keepScreenOn=${settings.keepScreenOnWhileViewingMap},profile=${settings.trackingProfile}"
    }

    private fun shouldIgnoreSettingChange(): Boolean {
        if (isBindingSettings) return true
        if (!hasHydratedSettings) {
            Log.d(TAG, "ignored_toggle_change reason=pre_hydration")
            return true
        }
        return false
    }

    companion object {
        private const val TAG = "SettingsDebug"
    }
}
