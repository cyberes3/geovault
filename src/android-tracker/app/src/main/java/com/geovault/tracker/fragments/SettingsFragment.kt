package com.geovault.tracker.fragments

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
import com.geovault.common.RetrofitClient
import com.geovault.tracker.*
import com.google.android.material.button.MaterialButton
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class SettingsFragment : Fragment() {

    private lateinit var serverUrlEdit: EditText
    private lateinit var connectButton: MaterialButton
    private lateinit var disconnectButton: MaterialButton
    private lateinit var loggedInUserText: TextView
    private lateinit var trackerSelectorCard: View
    private lateinit var selectedTrackerText: TextView
    private lateinit var trackerSaveProgress: View
    private lateinit var trackerSpinner: Spinner
    private lateinit var createTrackerButton: MaterialButton
    private lateinit var intervalEdit: EditText
    private lateinit var distanceEdit: EditText
    private lateinit var accuracyEdit: EditText
    private lateinit var extendedParamsSwitch: SwitchCompat
    private lateinit var significantMotionSwitch: SwitchCompat
    private lateinit var startOnBootSwitch: SwitchCompat

    private fun normalizeServerUrl(url: String): String {
        var serverUrl = url.trim().trimStart('/').trimEnd('/')
        if (serverUrl.isNotEmpty() && !serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            serverUrl = "https://$serverUrl"
        }
        return serverUrl
    }

    private var trackers: List<Tracker> = emptyList()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var trackerOperationTimeoutRunnable: Runnable? = null
    private var trackerOperationTimedOut = false
    private var trackerSaveRotationRunnable: Runnable? = null
    private var trackerSaveRotationStartTime = 0L
    private companion object {
        const val TRACKER_OPERATION_TIMEOUT_MS = 30_000L
        const val SPINNER_ROTATION_PERIOD_MS = 1000L
        const val SPINNER_FRAME_DELAY_MS = 16L
    }

    private fun setTrackerOperationInProgress(inProgress: Boolean) {
        createTrackerButton.isEnabled = !inProgress
        trackerSelectorCard.isClickable = !inProgress
        view?.findViewById<View>(R.id.selectedTrackerLabel)?.isClickable = !inProgress
        if (inProgress) {
            trackerSaveProgress.visibility = View.VISIBLE
            trackerSaveProgress.rotation = 0f
            trackerSaveRotationStartTime = SystemClock.elapsedRealtime()
            scheduleNextSpinnerFrame()
        } else {
            trackerSaveRotationRunnable?.let { mainHandler.removeCallbacks(it) }
            trackerSaveRotationRunnable = null
            trackerSaveProgress.visibility = View.GONE
        }
    }

    private fun scheduleNextSpinnerFrame() {
        if (trackerSaveProgress.visibility != View.VISIBLE) return
        val elapsed = SystemClock.elapsedRealtime() - trackerSaveRotationStartTime
        val fraction = (elapsed % SPINNER_ROTATION_PERIOD_MS).toFloat() / SPINNER_ROTATION_PERIOD_MS
        trackerSaveProgress.rotation = fraction * 360f
        trackerSaveRotationRunnable = Runnable {
            scheduleNextSpinnerFrame()
        }
        mainHandler.postDelayed(trackerSaveRotationRunnable!!, SPINNER_FRAME_DELAY_MS)
    }

    private fun scheduleTrackerOperationTimeout(onTimeout: () -> Unit) {
        cancelTrackerOperationTimeout()
        trackerOperationTimedOut = false
        trackerOperationTimeoutRunnable = Runnable {
            trackerOperationTimeoutRunnable = null
            trackerOperationTimedOut = true
            onTimeout()
        }
        mainHandler.postDelayed(trackerOperationTimeoutRunnable!!, TRACKER_OPERATION_TIMEOUT_MS)
    }

    private fun cancelTrackerOperationTimeout() {
        trackerOperationTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        trackerOperationTimeoutRunnable = null
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
        trackerSelectorCard = view.findViewById(R.id.trackerSelectorCard)
        selectedTrackerText = view.findViewById(R.id.selectedTrackerText)
        trackerSaveProgress = view.findViewById(R.id.trackerSaveProgress)
        trackerSpinner = view.findViewById(R.id.trackerSpinner)
        createTrackerButton = view.findViewById(R.id.createTrackerButton)
        intervalEdit = view.findViewById(R.id.intervalEdit)
        distanceEdit = view.findViewById(R.id.distanceEdit)
        accuracyEdit = view.findViewById(R.id.accuracyEdit)
        extendedParamsSwitch = view.findViewById(R.id.extendedParamsSwitch)
        significantMotionSwitch = view.findViewById(R.id.significantMotionSwitch)
        startOnBootSwitch = view.findViewById(R.id.startOnBootSwitch)

        loadSettings()
        updateUi()

        connectButton.setOnClickListener { onConnectClicked() }
        disconnectButton.setOnClickListener { onDisconnectClicked() }
        createTrackerButton.setOnClickListener { onCreateTrackerClicked() }
        trackerSelectorCard.setOnClickListener { showTrackerSelectionDialog() }
        view.findViewById<View>(R.id.selectedTrackerLabel).setOnClickListener { showTrackerSelectionDialog() }

        extendedParamsSwitch.setOnCheckedChangeListener { _, isChecked ->
            saveSetting("extended_params", isChecked)
        }

        significantMotionSwitch.setOnCheckedChangeListener { _, isChecked ->
            saveSetting("significant_motion_only", isChecked)
        }

        startOnBootSwitch.setOnCheckedChangeListener { _, isChecked ->
            saveSetting("start_on_boot", isChecked)
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

    private fun saveSelectedTracker(trackerId: String, trackerName: String) {
        requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE).edit()
            .putString("selected_tracker_id", trackerId)
            .putString("selected_tracker_name", trackerName)
            .commit()
    }

    private fun updateUi() {
        val isLoggedIn = GeovaultAuthManager.isLoggedIn(requireContext())
        connectButton.visibility = if (isLoggedIn) View.GONE else View.VISIBLE
        disconnectButton.visibility = if (isLoggedIn) View.VISIBLE else View.GONE
        loggedInUserText.visibility = if (isLoggedIn) View.VISIBLE else View.GONE
        createTrackerButton.visibility = if (isLoggedIn) View.VISIBLE else View.GONE
        
        val email = GeovaultAuthManager.getCachedUserEmail(requireContext())
        loggedInUserText.text = if (email != null) "Logged in as $email" else "Logged in"

        if (isLoggedIn) {
            showTrackerSpinnerPlaceholder()
            fetchTrackers()
        }
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

    private fun onCreateTrackerClicked() {
        val input = EditText(requireContext()).apply {
            hint = "Tracker name"
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Create New Tracker")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    createTracker(name)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createTracker(name: String) {
        val serverUrl = GeovaultAuthManager.getServerUrl(requireContext())
        if (serverUrl.isEmpty()) return

        setTrackerOperationInProgress(true)
                scheduleTrackerOperationTimeout {
            if (isAdded) {
                requireActivity().runOnUiThread {
                    setTrackerOperationInProgress(false)
                    (requireActivity() as? MainActivity)?.showSnackbar(getString(R.string.tracker_operation_timeout))
                }
            }
        }

        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(requireContext(), baseUrl).create(TrackerApi::class.java)

        api.createTracker(TrackerCreateRequest(name)).enqueue(object : Callback<Tracker> {
            override fun onResponse(call: Call<Tracker>, response: Response<Tracker>) {
                if (isAdded) {
                    requireActivity().runOnUiThread {
                        if (response.isSuccessful) {
                            val newTracker = response.body()
                            if (newTracker != null) {
                                saveSelectedTracker(newTracker.id, newTracker.name)
                                TrackerRepository.clearCurrentTrackerCache()
                                selectedTrackerText.text = newTracker.name
                                Toast.makeText(requireContext(), "Tracker '${newTracker.name}' created", Toast.LENGTH_SHORT).show()
                                fetchTrackers(forceRefresh = true) {
                                    cancelTrackerOperationTimeout()
                                    setTrackerOperationInProgress(false)
                                }
                            } else {
                                cancelTrackerOperationTimeout()
                                setTrackerOperationInProgress(false)
                            }
                        } else {
                            cancelTrackerOperationTimeout()
                            setTrackerOperationInProgress(false)
                            val body = response.errorBody()?.string()?.trim()?.takeIf { it.isNotEmpty() }
                            val msg = body?.let { it.take(120) + if (it.length > 120) "…" else "" }
                                ?: "server error ${response.code()}"
                            (requireActivity() as? MainActivity)?.showSnackbar("Failed to create tracker: $msg")
                        }
                    }
                }
            }
            override fun onFailure(call: Call<Tracker>, t: Throwable) {
                if (isAdded) {
                    requireActivity().runOnUiThread {
                        cancelTrackerOperationTimeout()
                        setTrackerOperationInProgress(false)
                        Log.e("SettingsFragment", "Failed to create tracker", t)
                        (requireActivity() as? MainActivity)?.showSnackbar("Failed to create tracker: ${t.message ?: "Network error"}")
                    }
                }
            }
        })
    }

    private fun showTrackerSpinnerPlaceholder() {
        val prefs = requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
        val savedName = prefs.getString("selected_tracker_name", null)
        selectedTrackerText.text = when {
            !savedName.isNullOrBlank() -> savedName
            else -> getString(R.string.loading_trackers)
        }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, listOf(selectedTrackerText.text))
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        trackerSpinner.adapter = adapter
    }

    private fun showTrackerSelectionDialog() {
        if (trackers.isEmpty()) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.select_tracker)
                .setMessage(getString(R.string.no_trackers_found_message))
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(getString(R.string.create_new_tracker)) { _, _ -> onCreateTrackerClicked() }
                .show()
            return
        }
        val names = trackers.map { it.name }.toTypedArray()
        val prefs = requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
        val currentId = prefs.getString("selected_tracker_id", null)
        var selectedIndex = trackers.indexOfFirst { it.id == currentId }
        if (selectedIndex < 0) selectedIndex = 0

        val selectDialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.select_tracker)
            .setSingleChoiceItems(names, selectedIndex) { dialog, which ->
                val tracker = trackers.getOrNull(which) ?: return@setSingleChoiceItems
                selectedTrackerText.text = tracker.name
                dialog.dismiss()
                saveSelectedTracker(tracker.id, tracker.name)
                TrackerRepository.clearCurrentTrackerCache()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        selectDialog.setOnDismissListener { fetchTrackers(forceRefresh = true) }
        selectDialog.show()
    }

    private fun fetchTrackers(forceRefresh: Boolean = false, onComplete: (() -> Unit)? = null) {
        TrackerRepository.getTrackers(requireContext(), forceRefresh) { list ->
            if (isAdded) {
                requireActivity().runOnUiThread {
                    trackers = list ?: emptyList()
                    setupTrackerSpinner()
                    onComplete?.invoke()
                }
            }
        }
    }

    private fun setupTrackerSpinner() {
        if (trackers.isEmpty()) {
            selectedTrackerText.text = getString(R.string.no_trackers_found)
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, listOf(getString(R.string.no_trackers_found)))
            trackerSpinner.adapter = adapter
            return
        }

        val names = trackers.map { it.name }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        trackerSpinner.adapter = adapter

        val prefs = requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
        val currentId = prefs.getString("selected_tracker_id", null)
        val currentIndex = trackers.indexOfFirst { it.id == currentId }
        if (currentIndex >= 0) {
            trackerSpinner.setSelection(currentIndex)
            selectedTrackerText.text = trackers[currentIndex].name
        } else {
            selectedTrackerText.text = trackers.firstOrNull()?.name ?: getString(R.string.select_tracker)
        }
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        trackerSaveRotationRunnable?.let { mainHandler.removeCallbacks(it) }
        trackerOperationTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
    }
}
