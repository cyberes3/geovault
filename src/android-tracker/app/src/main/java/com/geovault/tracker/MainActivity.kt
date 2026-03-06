package com.geovault.tracker

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.viewpager2.widget.ViewPager2
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.ImportantMessageSnackbar
import com.geovault.common.ServerUrlContract
import com.geovault.tracker.db.AppDatabase
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private var isGuestView: Boolean = false
    
    private lateinit var viewPager: ViewPager2
    private lateinit var pagerAdapter: MainPagerAdapter
    
    lateinit var database: AppDatabase
        private set
    private var isMainContentSetup = false
    /** True while validation/setup is in progress after user tapped Start; tapping Stop clears this and aborts. */
    private var isPreparingToTrack = false
    private var importantMessageSnackbar: ImportantMessageSnackbar? = null
    /** Tab indices we came from; back pops and navigates to the previous tab. */
    private val tabBackStack = ArrayDeque<Int>()
    /** Last selected tab index; pushed onto tabBackStack when user navigates to another tab. */
    private var lastSelectedTabIndex = -1
    /** True when handling back so we don't push the current tab onto tabBackStack. */
    private var isHandlingTabBack = false

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        updatePermissionsState()
    }
    
    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        updatePermissionsState()
    }
    
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        updatePermissionsState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == TrackingService.ACTION_STOP) {
            getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE).edit()
                .remove(TrackingService.PREF_WAS_TRACKING_BEFORE_EXIT).commit()
            startService(Intent(this, TrackingService::class.java).apply { action = TrackingService.ACTION_STOP })
        }
        if (!GeovaultAuthManager.isLoggedIn(this)) {
            isGuestView = true
            setContentView(R.layout.activity_main_guest)
            setupGuestView()
            return
        }
        setContentView(R.layout.activity_main)
        setupMainContent(savedInstanceState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == TrackingService.ACTION_STOP && !isGuestView) {
            getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE).edit()
                .remove(TrackingService.PREF_WAS_TRACKING_BEFORE_EXIT).commit()
            startService(Intent(this, TrackingService::class.java).apply { action = TrackingService.ACTION_STOP })
        }
    }

    private fun normalizeServerUrl(url: String): String {
        var serverUrl = url.trim().trimStart('/').trimEnd('/')
        if (serverUrl.isNotEmpty() && !serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            serverUrl = "https://$serverUrl"
        }
        return serverUrl
    }

    private fun setupGuestView() {
        val rootView = findViewById<View>(R.id.rootLayout)
        val headerLayout = findViewById<View>(R.id.headerLayout)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            headerLayout.updatePadding(top = insets.top + 20)
            view.updatePadding(bottom = insets.bottom)
            view.findViewById<View>(R.id.importantMessageSnackbar)?.updatePadding(bottom = insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
        importantMessageSnackbar = findViewById(R.id.importantMessageSnackbar)
        val serverUrlEdit = findViewById<EditText>(R.id.guestServerUrlEdit)
        val serverUrl = GeovaultAuthManager.getServerUrl(this)
        if (serverUrl.isNotEmpty()) {
            serverUrlEdit.setText(serverUrl)
        } else {
            val otherUrls = ServerUrlContract.getServerUrlsFromOtherApps(this)
            if (otherUrls.size == 1) {
                serverUrlEdit.setText(otherUrls.single())
            }
        }
        findViewById<MaterialButton>(R.id.guestConnectButton).setOnClickListener {
            val url = normalizeServerUrl(serverUrlEdit.text.toString())
            if (url.isEmpty()) {
                showSnackbar("Please enter server URL")
                return@setOnClickListener
            }
            GeovaultAuthManager.setServerUrl(this, url)
            val (verifier, challenge) = GeovaultAuthManager.generatePkcePair()
            val state = java.util.UUID.randomUUID().toString()
            GeovaultAuthManager.savePkceState(this, verifier, state)
            val authUrl = GeovaultAuthManager.buildAuthorizeUrl(url, challenge, state)
            GeovaultAuthManager.launchOAuthInBrowser(this, authUrl)
        }
    }

    private fun setupMainContent(savedInstanceState: Bundle?) {
        if (isMainContentSetup) {
            return
        }
        isMainContentSetup = true
        val rootView = findViewById<View>(R.id.rootLayout)
        val headerLayout = findViewById<View>(R.id.headerLayout)
        val mainContentLayout = findViewById<View>(R.id.mainContentLayout)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            headerLayout.updatePadding(top = insets.top + 20)
            mainContentLayout.updatePadding(bottom = insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(rootView)

        importantMessageSnackbar = findViewById(R.id.importantMessageSnackbar)
        database = AppDatabase.getDatabase(this)

        viewPager = findViewById(R.id.viewPager)
        pagerAdapter = MainPagerAdapter(this)
        viewPager.adapter = pagerAdapter
        
        viewPager.isUserInputEnabled = false
        viewPager.offscreenPageLimit = 3  // Keep all 4 pages in memory

        val savedTab = (savedInstanceState?.getInt(KEY_CURRENT_TAB, 0) ?: 0).coerceIn(0, 3)
        viewPager.setCurrentItem(savedTab, false)
        
        viewPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                if (!isHandlingTabBack && lastSelectedTabIndex >= 0) {
                    tabBackStack.addLast(lastSelectedTabIndex)
                }
                lastSelectedTabIndex = position
                updateNavTabBackground(position)
            }
        })

        findViewById<View>(R.id.navHome).setOnClickListener {
            viewPager.setCurrentItem(0, false)
        }
        findViewById<View>(R.id.navMap).setOnClickListener {
            viewPager.setCurrentItem(1, false)
        }
        findViewById<View>(R.id.navTrackers).setOnClickListener {
            viewPager.setCurrentItem(2, false)
        }
        findViewById<View>(R.id.navSettings).setOnClickListener {
            viewPager.setCurrentItem(3, false)
        }

        updateNavTabBackground(savedTab)
        updatePermissionsState()

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (supportFragmentManager.backStackEntryCount > 0) {
                        supportFragmentManager.popBackStack()
                    } else if (tabBackStack.isNotEmpty()) {
                        isHandlingTabBack = true
                        viewPager.setCurrentItem(tabBackStack.removeLast(), false)
                        isHandlingTabBack = false
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )

        // Show "Connected as ..." toast after layout so it appears in the correct position (bottom)
        intent?.getStringExtra(EXTRA_SIGNED_IN_EMAIL)?.let { email ->
            rootView.post {
                Toast.makeText(this, "Connected as $email", Toast.LENGTH_SHORT).show()
            }
            intent.removeExtra(EXTRA_SIGNED_IN_EMAIL)
        }

        // Restart-if-killed and start-on-launch
        val prefs = getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
        val restartIfKilled = prefs.getBoolean("restart_tracking_if_killed", true)
        val wasTrackingBeforeExit = prefs.getBoolean(TrackingService.PREF_WAS_TRACKING_BEFORE_EXIT, false)
        if (!restartIfKilled || TrackingService.isRunning) {
            if (wasTrackingBeforeExit) {
                prefs.edit().remove(TrackingService.PREF_WAS_TRACKING_BEFORE_EXIT).commit()
            }
        } else if (wasTrackingBeforeExit) {
            tryResumeTrackingAfterKill()
            return
        }
        if (!TrackingService.isRunning && prefs.getBoolean("start_tracking_on_launch", false)) {
            tryStartTrackingOnLaunch()
        }
    }

    private fun tryResumeTrackingAfterKill() {
        val prefs = getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
        val trackerId = prefs.getString("selected_tracker_id", "") ?: ""
        if (trackerId.isEmpty()) {
            prefs.edit().remove(TrackingService.PREF_WAS_TRACKING_BEFORE_EXIT).commit()
            return
        }
        tryStartTrackingSilently(
            onInvalid = {
                prefs.edit()
                    .remove("selected_tracker_id")
                    .remove("selected_tracker_name")
                    .remove(TrackingService.PREF_WAS_TRACKING_BEFORE_EXIT)
                    .commit()
                TrackerRepository.clearCurrentTrackerCache()
                TrackerRepository.clearCache()
                TrackerRepository.getTrackers(this, forceRefresh = true) { }
                showSnackbar(getString(R.string.tracker_validation_failed_go_to_settings))
                val hf = pagerAdapter.getFragment(0) as? com.geovault.tracker.fragments.HomeFragment
                hf?.updateTrackingUi()
            },
            onValid = {
                val intent = Intent(this, TrackingService::class.java).apply { action = TrackingService.ACTION_START }
                startForegroundService(intent)
                Handler(Looper.getMainLooper()).postDelayed({
                    val hf = pagerAdapter.getFragment(0) as? com.geovault.tracker.fragments.HomeFragment
                    hf?.updateTrackingUi()
                }, 300)
                Toast.makeText(this, getString(R.string.resuming_tracking), Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun tryStartTrackingOnLaunch() {
        val prefs = getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
        val trackerId = prefs.getString("selected_tracker_id", "") ?: ""
        if (trackerId.isEmpty()) return
        tryStartTrackingSilently(
            onInvalid = {
                prefs.edit()
                    .remove("selected_tracker_id")
                    .remove("selected_tracker_name")
                    .apply()
                TrackerRepository.clearCurrentTrackerCache()
                TrackerRepository.clearCache()
                TrackerRepository.getTrackers(this, forceRefresh = true) { }
                showSnackbar(getString(R.string.tracker_validation_failed_go_to_settings))
                val hf = pagerAdapter.getFragment(0) as? com.geovault.tracker.fragments.HomeFragment
                hf?.updateTrackingUi()
            },
            onValid = {
                val intent = Intent(this, TrackingService::class.java).apply { action = TrackingService.ACTION_START }
                startForegroundService(intent)
                Handler(Looper.getMainLooper()).postDelayed({
                    val hf = pagerAdapter.getFragment(0) as? com.geovault.tracker.fragments.HomeFragment
                    hf?.updateTrackingUi()
                }, 300)
                Toast.makeText(this, getString(R.string.tracking_started), Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun tryStartTrackingSilently(
        onInvalid: () -> Unit,
        onValid: () -> Unit
    ) {
        val prefs = getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
        val trackerId = prefs.getString("selected_tracker_id", "") ?: ""
        if (trackerId.isEmpty()) return
        TrackerRepository.checkTracker(this, trackerId) { valid ->
            runOnUiThread {
                if (valid) onValid() else onInvalid()
            }
        }
    }
    
    private fun updateNavTabBackground(position: Int) {
        val navHome = findViewById<View>(R.id.navHome)
        val navMap = findViewById<View>(R.id.navMap)
        val navTrackers = findViewById<View>(R.id.navTrackers)
        val navSettings = findViewById<View>(R.id.navSettings)

        val yellowColor = ContextCompat.getColor(this, R.color.warning_yellow)
        val whiteColor = ContextCompat.getColor(this, R.color.content_on_primary)

        updateNavTabColors(navHome, position == 0, yellowColor, whiteColor)
        updateNavTabColors(navMap, position == 1, yellowColor, whiteColor)
        updateNavTabColors(navTrackers, position == 2, yellowColor, whiteColor)
        updateNavTabColors(navSettings, position == 3, yellowColor, whiteColor)
    }
    
    private fun updateNavTabColors(navView: View, isSelected: Boolean, selectedColor: Int, defaultColor: Int) {
        val color = if (isSelected) selectedColor else defaultColor
        
        // Update icon color
        val icon = navView.findViewById<android.widget.ImageView>(
            when (navView.id) {
                R.id.navHome -> R.id.navHomeIcon
                R.id.navMap -> R.id.navMapIcon
                R.id.navTrackers -> R.id.navTrackersIcon
                R.id.navSettings -> R.id.navSettingsIcon
                else -> return
            }
        )
        icon?.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)

        // Update text color
        val text = navView.findViewById<android.widget.TextView>(
            when (navView.id) {
                R.id.navHome -> R.id.navHomeText
                R.id.navMap -> R.id.navMapText
                R.id.navTrackers -> R.id.navTrackersText
                R.id.navSettings -> R.id.navSettingsText
                else -> return
            }
        )
        text?.setTextColor(color)
    }

    fun setCurrentTab(index: Int, forceRefreshMap: Boolean = false) {
        viewPager.setCurrentItem(index, false)
        if (forceRefreshMap && index == 1) {
            val mapFragment = pagerAdapter.getFragment(1) as? com.geovault.tracker.fragments.MapFragment
            mapFragment?.refreshTrackForSelectedTracker()
        }
    }

    fun showNewTrackerFragment() {
        supportFragmentManager.beginTransaction()
            .add(R.id.fragment_overlay_container, com.geovault.tracker.fragments.NewTrackerFragment(), "new_tracker")
            .addToBackStack("new_tracker")
            .commit()
    }

    fun showEditTrackerFragment(tracker: com.geovault.tracker.Tracker) {
        val fragment = com.geovault.tracker.fragments.EditTrackerFragment().apply {
            arguments = android.os.Bundle().apply {
                putParcelable(com.geovault.tracker.fragments.EditTrackerFragment.ARG_TRACKER, tracker)
            }
        }
        supportFragmentManager.beginTransaction()
            .add(R.id.fragment_overlay_container, fragment, "edit_tracker")
            .addToBackStack("edit_tracker")
            .commit()
    }

    fun showTrackerParamsFragment(
        trackerId: String,
        trackerName: String? = null,
        lastUpdateMs: Long? = null,
        positionLat: Double? = null,
        positionLon: Double? = null
    ) {
        val fragment = com.geovault.tracker.fragments.TrackerParamsFragment().apply {
            arguments = android.os.Bundle().apply {
                putString(com.geovault.tracker.fragments.TrackerParamsFragment.ARG_TRACKER_ID, trackerId)
                trackerName?.let { putString(com.geovault.tracker.fragments.TrackerParamsFragment.ARG_TRACKER_NAME, it) }
                lastUpdateMs?.let { putLong(com.geovault.tracker.fragments.TrackerParamsFragment.ARG_LAST_UPDATE_MS, it) }
                positionLat?.let { putDouble(com.geovault.tracker.fragments.TrackerParamsFragment.ARG_POSITION_LAT, it) }
                positionLon?.let { putDouble(com.geovault.tracker.fragments.TrackerParamsFragment.ARG_POSITION_LON, it) }
            }
        }
        supportFragmentManager.beginTransaction()
            .add(R.id.fragment_overlay_container, fragment, "tracker_params")
            .addToBackStack("tracker_params")
            .commit()
    }
    
    /** Show an important dismissable message. Use for errors and blocking issues. */
    fun showSnackbar(message: String) {
        importantMessageSnackbar?.showMessage(message)
    }
    
    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
               ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    
    fun hasBackgroundLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    
    fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    
    fun hasBatteryOptimizationExemption(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }
    
    fun hasAllRequiredPermissions(): Boolean {
        return hasLocationPermission() && hasBackgroundLocationPermission() && 
               hasNotificationPermission() && hasBatteryOptimizationExemption()
    }
    
    fun requestLocationPermission() {
        locationPermissionLauncher.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ))
    }
    
    fun requestBackgroundLocationPermission() {
        if (!hasLocationPermission()) {
            showSnackbar(getString(R.string.location_permission_needed_first))
            return
        }
        backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }
    
    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    
    fun requestBatteryOptimizationExemption() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }
    
    private fun updatePermissionsState() {
        val homeFragment = pagerAdapter.getFragment(0) as? com.geovault.tracker.fragments.HomeFragment
        homeFragment?.updatePermissionsUi()
    }

    private fun showStopTrackingConfirmation(onConfirm: () -> Unit) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.stop_tracking_confirm_title))
            .setPositiveButton(getString(R.string.stop_tracking)) { _, _ -> onConfirm() }
            .setNegativeButton(getString(R.string.cancel_button), null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(
            ContextCompat.getColor(this, com.geovault.common.R.color.gv_common_dialog_positive_button)
        )
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(
            ContextCompat.getColor(this, com.geovault.common.R.color.gv_common_dialog_negative_button)
        )
    }

    fun toggleTracking() {
        val homeFragment = pagerAdapter.getFragment(0) as? com.geovault.tracker.fragments.HomeFragment
        if (isPreparingToTrack) {
            showStopTrackingConfirmation {
                isPreparingToTrack = false
                homeFragment?.updateTrackingUi()
            }
            return
        }
        val intent = Intent(this, TrackingService::class.java)
        if (TrackingService.isRunning) {
            showStopTrackingConfirmation {
                getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE).edit()
                    .remove(TrackingService.PREF_WAS_TRACKING_BEFORE_EXIT).commit()
                intent.action = TrackingService.ACTION_STOP
                startService(intent)
                Handler(Looper.getMainLooper()).postDelayed({
                    homeFragment?.updateTrackingUi()
                }, 300)
            }
            return
        }
        val prefs = getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
        val trackerId = prefs.getString("selected_tracker_id", "") ?: ""
        if (trackerId.isEmpty()) {
            showSnackbar(getString(R.string.no_tracker_selected_go_to_settings))
            return
        }
        isPreparingToTrack = true
        homeFragment?.showPreparingState()
        TrackerRepository.checkTracker(this, trackerId) { valid ->
            runOnUiThread {
                if (!isPreparingToTrack) return@runOnUiThread
                isPreparingToTrack = false
                if (!valid) {
                    prefs.edit()
                        .remove("selected_tracker_id")
                        .remove("selected_tracker_name")
                        .apply()
                    TrackerRepository.clearCurrentTrackerCache()
                    TrackerRepository.clearCache()
                    TrackerRepository.getTrackers(this@MainActivity, forceRefresh = true) { }
                    showSnackbar(getString(R.string.tracker_validation_failed_go_to_settings))
                    val hf = pagerAdapter.getFragment(0) as? com.geovault.tracker.fragments.HomeFragment
                    hf?.updateTrackingUi()
                    return@runOnUiThread
                }
                intent.action = TrackingService.ACTION_START
                startForegroundService(intent)
                Handler(Looper.getMainLooper()).postDelayed({
                    val hf = pagerAdapter.getFragment(0) as? com.geovault.tracker.fragments.HomeFragment
                    hf?.updateTrackingUi()
                }, 300)
            }
        }
    }

    fun updateQueueCountFromFragment(textView: TextView) {
        CoroutineScope(Dispatchers.Main).launch {
            val count = withContext(Dispatchers.IO) { database.locationDao().getCount() }
            textView.text = count.toString()
        }
    }

    override fun onStart() {
        super.onStart()
        if (isGuestView) {
            if (GeovaultAuthManager.isLoggedIn(this)) {
                isGuestView = false
                setContentView(R.layout.activity_main)
                setupMainContent(null)
            } else {
                return
            }
        }
        GeovaultAuthManager.fetchUserStatus(this)
        if (!isGuestView && isMainContentSetup) {
            TrackerRepository.getTrackers(this, forceRefresh = true) { }
        }
        updatePermissionsState()
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!isGuestView) {
            outState.putInt(KEY_CURRENT_TAB, viewPager.currentItem)
        }
    }

    companion object {
        private const val KEY_CURRENT_TAB = "current_tab"
        const val EXTRA_SIGNED_IN_EMAIL = "signed_in_email"
    }
}
