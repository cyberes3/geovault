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
import androidx.activity.result.contract.ActivityResultContracts
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
    private var importantMessageSnackbar: ImportantMessageSnackbar? = null

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
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, windowInsets ->
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
        viewPager.offscreenPageLimit = 2  // Keep all 3 pages in memory
        
        val savedTab = savedInstanceState?.getInt(KEY_CURRENT_TAB, 0) ?: 0
        viewPager.setCurrentItem(savedTab, false)
        
        viewPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                updateNavTabBackground(position)
            }
        })

        findViewById<View>(R.id.navHome).setOnClickListener {
            viewPager.setCurrentItem(0, false)
        }
        findViewById<View>(R.id.navMap).setOnClickListener {
            viewPager.setCurrentItem(1, false)
        }
        findViewById<View>(R.id.navSettings).setOnClickListener {
            viewPager.setCurrentItem(2, false)
        }

        updateNavTabBackground(savedTab)
        updatePermissionsState()
    }
    
    private fun updateNavTabBackground(position: Int) {
        val navHome = findViewById<View>(R.id.navHome)
        val navMap = findViewById<View>(R.id.navMap)
        val navSettings = findViewById<View>(R.id.navSettings)
        
        val yellowColor = ContextCompat.getColor(this, R.color.warning_yellow)
        val whiteColor = ContextCompat.getColor(this, R.color.content_on_primary)
        
        // Home tab
        updateNavTabColors(navHome, position == 0, yellowColor, whiteColor)
        // Map tab
        updateNavTabColors(navMap, position == 1, yellowColor, whiteColor)
        // Settings tab
        updateNavTabColors(navSettings, position == 2, yellowColor, whiteColor)
    }
    
    private fun updateNavTabColors(navView: View, isSelected: Boolean, selectedColor: Int, defaultColor: Int) {
        val color = if (isSelected) selectedColor else defaultColor
        
        // Update icon color
        val icon = navView.findViewById<android.widget.ImageView>(
            when (navView.id) {
                R.id.navHome -> R.id.navHomeIcon
                R.id.navMap -> R.id.navMapIcon
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
                R.id.navSettings -> R.id.navSettingsText
                else -> return
            }
        )
        text?.setTextColor(color)
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

    fun toggleTracking() {
        val intent = Intent(this, TrackingService::class.java)
        if (TrackingService.isRunning) {
            intent.action = TrackingService.ACTION_STOP
            startService(intent)
            Handler(Looper.getMainLooper()).postDelayed({
                val homeFragment = pagerAdapter.getFragment(0) as? com.geovault.tracker.fragments.HomeFragment
                homeFragment?.updateTrackingUi()
            }, 300)
            return
        }
        val prefs = getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
        val trackerId = prefs.getString("selected_tracker_id", "") ?: ""
        if (trackerId.isEmpty()) {
            showSnackbar(getString(R.string.no_tracker_selected_go_to_settings))
            return
        }
        TrackerRepository.checkTracker(this, trackerId) { valid ->
            runOnUiThread {
                if (!valid) {
                    prefs.edit()
                        .remove("selected_tracker_id")
                        .remove("selected_tracker_name")
                        .apply()
                    TrackerRepository.clearCurrentTrackerCache()
                    TrackerRepository.clearCache()
                    TrackerRepository.getTrackers(this@MainActivity, forceRefresh = true) { }
                    showSnackbar(getString(R.string.no_tracker_selected_go_to_settings))
                    val homeFragment = pagerAdapter.getFragment(0) as? com.geovault.tracker.fragments.HomeFragment
                    homeFragment?.updateTrackingUi()
                    return@runOnUiThread
                }
                intent.action = TrackingService.ACTION_START
                startForegroundService(intent)
                Handler(Looper.getMainLooper()).postDelayed({
                    val homeFragment = pagerAdapter.getFragment(0) as? com.geovault.tracker.fragments.HomeFragment
                    homeFragment?.updateTrackingUi()
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
    }
}
