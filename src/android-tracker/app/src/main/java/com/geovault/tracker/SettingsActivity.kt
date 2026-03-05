package com.geovault.tracker

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.ImportantMessageSnackbar
import com.geovault.common.RetrofitClient
import com.google.android.material.button.MaterialButton
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class SettingsActivity : AppCompatActivity() {

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
    private lateinit var importantMessageSnackbar: ImportantMessageSnackbar

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
        findViewById<View>(R.id.selectedTrackerLabel).isClickable = !inProgress
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

    /** Updates spinner rotation every frame using elapsed time so it works when animator scale is 0. */
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val rootView = findViewById<View>(R.id.rootLayout)
        val headerLayout = findViewById<View>(R.id.headerLayout)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            headerLayout.updatePadding(top = systemBars.top + 20)
            val bottomInset = if (ime.bottom > systemBars.bottom) ime.bottom else systemBars.bottom
            view.updatePadding(bottom = bottomInset)
            windowInsets
        }

        serverUrlEdit = findViewById(R.id.serverUrlEdit)
        importantMessageSnackbar = findViewById(R.id.importantMessageSnackbar)
        connectButton = findViewById(R.id.connectButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        loggedInUserText = findViewById(R.id.loggedInUserText)
        trackerSelectorCard = findViewById(R.id.trackerSelectorCard)
        selectedTrackerText = findViewById(R.id.selectedTrackerText)
        trackerSaveProgress = findViewById(R.id.trackerSaveProgress)
        trackerSpinner = findViewById(R.id.trackerSpinner)
        createTrackerButton = findViewById(R.id.createTrackerButton)
        intervalEdit = findViewById(R.id.intervalEdit)
        distanceEdit = findViewById(R.id.distanceEdit)
        accuracyEdit = findViewById(R.id.accuracyEdit)
        extendedParamsSwitch = findViewById(R.id.extendedParamsSwitch)
        significantMotionSwitch = findViewById(R.id.significantMotionSwitch)
        startOnBootSwitch = findViewById(R.id.startOnBootSwitch)

        loadSettings()
        updateUi()

        connectButton.setOnClickListener { onConnectClicked() }
        disconnectButton.setOnClickListener { onDisconnectClicked() }
        createTrackerButton.setOnClickListener { onCreateTrackerClicked() }
        trackerSelectorCard.setOnClickListener { showTrackerSelectionDialog() }
        findViewById<View>(R.id.selectedTrackerLabel).setOnClickListener { showTrackerSelectionDialog() }

        extendedParamsSwitch.setOnCheckedChangeListener { _, isChecked ->
            saveSetting("extended_params", isChecked)
        }

        significantMotionSwitch.setOnCheckedChangeListener { _, isChecked ->
            saveSetting("significant_motion_only", isChecked)
        }

        startOnBootSwitch.setOnCheckedChangeListener { _, isChecked ->
            saveSetting("start_on_boot", isChecked)
        }

        findViewById<View>(R.id.loggingHelpButton).setOnClickListener { showLoggingHelpDialog() }
    }

    private fun showSnackbar(message: String) {
        importantMessageSnackbar.showMessage(message)
    }

    private fun showLoggingHelpDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.logging_help_title))
            .setMessage(getString(R.string.logging_help_message))
            .setPositiveButton(getString(R.string.close), null)
            .show()
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
        val serverUrl = GeovaultAuthManager.getServerUrl(this)
        if (serverUrl.isNotEmpty()) {
            serverUrlEdit.setText(serverUrl)
        } else {
            val otherUrls = com.geovault.common.ServerUrlContract.getServerUrlsFromOtherApps(this)
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
        getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE).edit().putString(key, value).apply()
    }

    private fun saveSetting(key: String, value: Boolean) {
        getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE).edit().putBoolean(key, value).apply()
    }

    /** Saves the selected tracker immediately (synchronous) so it is persisted before the dialog closes. */
    private fun saveSelectedTracker(trackerId: String, trackerName: String) {
        getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE).edit()
            .putString("selected_tracker_id", trackerId)
            .putString("selected_tracker_name", trackerName)
            .commit()
    }

    private fun updateUi() {
        val isLoggedIn = GeovaultAuthManager.isLoggedIn(this)
        connectButton.visibility = if (isLoggedIn) View.GONE else View.VISIBLE
        disconnectButton.visibility = if (isLoggedIn) View.VISIBLE else View.GONE
        loggedInUserText.visibility = if (isLoggedIn) View.VISIBLE else View.GONE
        createTrackerButton.visibility = if (isLoggedIn) View.VISIBLE else View.GONE
        
        val email = GeovaultAuthManager.getCachedUserEmail(this)
        loggedInUserText.text = if (email != null) "Logged in as $email" else "Logged in"

        if (isLoggedIn) {
            showTrackerSpinnerPlaceholder()
            fetchTrackers()
        }
    }

    private fun onConnectClicked() {
        val url = normalizeServerUrl(serverUrlEdit.text.toString())
        if (url.isEmpty()) {
            showSnackbar("Please enter server URL")
            return
        }
        GeovaultAuthManager.setServerUrl(this, url)
        
        val (verifier, challenge) = GeovaultAuthManager.generatePkcePair()
        val state = java.util.UUID.randomUUID().toString()
        GeovaultAuthManager.savePkceState(this, verifier, state)
        
        val authUrl = GeovaultAuthManager.buildAuthorizeUrl(url, challenge, state)
        GeovaultAuthManager.launchOAuthInBrowser(this, authUrl)
    }

    private fun onDisconnectClicked() {
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.disconnect_confirm_title))
            .setMessage(getString(R.string.disconnect_confirm_message))
            .setPositiveButton(getString(R.string.disconnect)) { _, _ ->
                GeovaultAuthManager.revokeToken(this, GeovaultAuthManager.getAccessToken(this))
                GeovaultAuthManager.revokeToken(this, GeovaultAuthManager.getRefreshToken(this))
                GeovaultAuthManager.clearTokens(this)
                updateUi()
                Toast.makeText(this, getString(R.string.disconnect), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel_button), null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(
            ContextCompat.getColor(this, R.color.error_red)
        )
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(
            ContextCompat.getColor(this, com.geovault.common.R.color.gv_common_dialog_negative_button)
        )
    }

    private fun onCreateTrackerClicked() {
        val input = EditText(this).apply {
            hint = "Tracker name"
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
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
        val serverUrl = GeovaultAuthManager.getServerUrl(this)
        if (serverUrl.isEmpty()) return

        setTrackerOperationInProgress(true)
        scheduleTrackerOperationTimeout {
            runOnUiThread {
                setTrackerOperationInProgress(false)
                showSnackbar(getString(R.string.tracker_operation_timeout))
            }
        }

        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(this, baseUrl).create(TrackerApi::class.java)

        api.createTracker(TrackerCreateRequest(name)).enqueue(object : Callback<Tracker> {
            override fun onResponse(call: Call<Tracker>, response: Response<Tracker>) {
                runOnUiThread {
                    if (response.isSuccessful) {
                        val newTracker = response.body()
                        if (newTracker != null) {
                            saveSelectedTracker(newTracker.id, newTracker.name)
                            TrackerRepository.clearCurrentTrackerCache()
                            selectedTrackerText.text = newTracker.name
                            Toast.makeText(this@SettingsActivity, "Tracker '${newTracker.name}' created", Toast.LENGTH_SHORT).show()
                            // Timeout stays active; clear loading when list refresh completes
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
                        showSnackbar("Failed to create tracker: $msg")
                    }
                }
            }
            override fun onFailure(call: Call<Tracker>, t: Throwable) {
                runOnUiThread {
                    cancelTrackerOperationTimeout()
                    setTrackerOperationInProgress(false)
                    Log.e("SettingsActivity", "Failed to create tracker", t)
                    showSnackbar("Failed to create tracker: ${t.message ?: "Network error"}")
                }
            }
        })
    }

    private fun showTrackerSpinnerPlaceholder() {
        val prefs = getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
        val savedName = prefs.getString("selected_tracker_name", null)
        selectedTrackerText.text = when {
            !savedName.isNullOrBlank() -> savedName
            else -> getString(R.string.loading_trackers)
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, listOf(selectedTrackerText.text))
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        trackerSpinner.adapter = adapter
    }

    private fun showTrackerSelectionDialog() {
        if (trackers.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.select_tracker)
                .setMessage(getString(R.string.no_trackers_found_message))
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(getString(R.string.create_new_tracker)) { _, _ -> onCreateTrackerClicked() }
                .show()
            return
        }
        val names = trackers.map { it.name }.toTypedArray()
        val prefs = getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
        val currentId = prefs.getString("selected_tracker_id", null)
        var selectedIndex = trackers.indexOfFirst { it.id == currentId }
        if (selectedIndex < 0) selectedIndex = 0

        val selectDialog = AlertDialog.Builder(this)
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
        TrackerRepository.getTrackers(this, forceRefresh) { list ->
            runOnUiThread {
                trackers = list ?: emptyList()
                setupTrackerSpinner()
                onComplete?.invoke()
            }
        }
    }

    private fun setupTrackerSpinner() {
        if (trackers.isEmpty()) {
            selectedTrackerText.text = getString(R.string.no_trackers_found)
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, listOf(getString(R.string.no_trackers_found)))
            trackerSpinner.adapter = adapter
            return
        }

        val names = trackers.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        trackerSpinner.adapter = adapter

        val prefs = getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
        val currentId = prefs.getString("selected_tracker_id", null)
        val currentIndex = trackers.indexOfFirst { it.id == currentId }
        if (currentIndex >= 0) {
            trackerSpinner.setSelection(currentIndex)
            selectedTrackerText.text = trackers[currentIndex].name
        } else {
            selectedTrackerText.text = trackers.firstOrNull()?.name ?: getString(R.string.select_tracker)
        }
    }
}
