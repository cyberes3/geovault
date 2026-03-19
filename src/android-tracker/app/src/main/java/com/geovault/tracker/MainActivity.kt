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
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.ImportantMessageSnackbar
import com.geovault.common.ServerUrlContract
import com.geovault.tracker.Group
import com.geovault.tracker.db.AppDatabase
import com.geovault.tracker.navigation.TrackerNavHost
import com.geovault.tracker.settings.TrackerSettingsRepository
import com.geovault.tracker.services.LiveStreamRuntimeStateStore
import com.geovault.tracker.services.TrackingRuntimeStateStore
import com.geovault.tracker.startup.RepositoryStartupRefreshGateway
import com.geovault.tracker.startup.StartupRefreshInput
import com.geovault.tracker.startup.StartupRefreshOrchestrator
import com.google.android.material.button.MaterialButton
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity(), TrackerNavHost {
    @Inject
    lateinit var settingsRepository: TrackerSettingsRepository

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
    private val startupRefreshOrchestrator = StartupRefreshOrchestrator(RepositoryStartupRefreshGateway())
    private var startupRefreshJob: Job? = null
    override var isServerAccessible = true
        private set
    private var trackingErrorReceiverRegistered = false
    private var streamingErrorReceiverRegistered = false
    private val trackingErrorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != TrackingService.ACTION_TRACKING_ERROR) return
            val message = intent.getStringExtra(TrackingService.EXTRA_TRACKING_ERROR_MESSAGE)
            if (!message.isNullOrBlank()) {
                showSnackbar(message)
            }
            val homeFragment = pagerAdapter.getFragment(0) as? com.geovault.tracker.fragments.HomeFragment
            homeFragment?.updateTrackingUi()
        }
    }
    private val streamingErrorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = extractStreamingErrorMessage(intent)
            if (!message.isNullOrBlank()) {
                showSnackbar(message)
            }
        }
    }

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
        handleIntentAction(intent)
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
        if (!isGuestView) {
            handleIntentAction(intent)
        }
    }

    private fun handleIntentAction(intent: Intent?) {
        val action = intent?.action ?: return
        if (action == TrackingService.ACTION_STOP) {
            settingsRepository.clearWasTrackingBeforeExit()
            startService(Intent(this, TrackingService::class.java).apply { this.action = action })
        } else if (action == LiveTrackStreamingService.ACTION_STOP) {
            startService(Intent(this, LiveTrackStreamingService::class.java).apply { this.action = action })
            if (isMainContentSetup) {
                (pagerAdapter.getFragment(1) as? com.geovault.tracker.fragments.map.MapFragment)?.restoreTrackForSelectedTracker()
            }
        }
    }

    private fun setupGuestView() {
        val rootView = findViewById<View>(R.id.rootLayout)
        val headerLayout = findViewById<View>(R.id.headerLayout)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomInset = if (ime.bottom > systemBars.bottom) ime.bottom else systemBars.bottom
            headerLayout.updatePadding(top = systemBars.top + 20)
            view.updatePadding(bottom = bottomInset)
            view.findViewById<View>(R.id.importantMessageSnackbar)?.updatePadding(bottom = bottomInset)
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
        val guestConnectButton = findViewById<MaterialButton>(R.id.guestConnectButton)
        guestConnectButton.setOnClickListener {
            val url = GeovaultAuthManager.normalizeServerUrl(serverUrlEdit.text.toString())
            if (url.isEmpty()) {
                showSnackbar(getString(R.string.error_enter_server_url))
                return@setOnClickListener
            }
            guestConnectButton.isEnabled = false
            showSnackbar(getString(R.string.connecting_server))
            GeovaultAuthManager.resolveServerUrlToCanonical(url) { result ->
                runOnUiThread {
                    guestConnectButton.isEnabled = true
                    result.fold(
                        onSuccess = { resolvedUrl ->
                            GeovaultAuthManager.setServerUrl(this, resolvedUrl)
                            val (verifier, challenge) = GeovaultAuthManager.generatePkcePair()
                            val state = java.util.UUID.randomUUID().toString()
                            GeovaultAuthManager.savePkceState(this, verifier, state)
                            val authUrl = GeovaultAuthManager.buildAuthorizeUrl(resolvedUrl, challenge, state)
                            GeovaultAuthManager.launchOAuthInBrowser(this, authUrl)
                        },
                        onFailure = {
                            showSnackbar(getString(R.string.error_server_unreachable))
                        }
                    )
                }
            }
        }
    }

    private fun runStartupRefresh(savedTab: Int) {
        if (startupRefreshJob?.isActive == true) return
        val selectedTrackerId = SelectedTrackerPrefs.selectedTrackerId(this)
        startupRefreshJob = lifecycleScope.launch {
            val result = startupRefreshOrchestrator.run(
                context = this@MainActivity,
                input = StartupRefreshInput(
                    selectedTrackerId = selectedTrackerId,
                    savedTab = savedTab
                )
            )
            setServerAccessibility(result.serverAccessible)
            result.selectedTrackerForMap?.let { updateInitialTrackForMapIfPending(it) }
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
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomInset = if (ime.bottom > systemBars.bottom) ime.bottom else systemBars.bottom
            headerLayout.updatePadding(top = systemBars.top + 20)
            mainContentLayout.updatePadding(bottom = bottomInset)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(rootView)

        importantMessageSnackbar = findViewById(R.id.importantMessageSnackbar)
        database = AppDatabase.getDatabase(this)

        viewPager = findViewById(R.id.viewPager)
        viewPager.overScrollMode = View.OVER_SCROLL_NEVER
        pagerAdapter = MainPagerAdapter(this)
        viewPager.adapter = pagerAdapter
        
        viewPager.isUserInputEnabled = false
        viewPager.offscreenPageLimit = 4  // Keep all 5 pages in memory

        val savedTab = (savedInstanceState?.getInt(KEY_CURRENT_TAB, 0) ?: 0).coerceIn(0, 4)
        viewPager.setCurrentItem(savedTab, false)
        lastSelectedTabIndex = savedTab
        tabBackStack.clear()
        savedInstanceState?.getIntegerArrayList(KEY_TAB_BACK_STACK)?.forEach { tab ->
            if (tab in 0..4 && tab != savedTab) {
                tabBackStack.addLast(tab)
            }
        }
        initialTrackForMap = savedInstanceState?.getParcelable(KEY_INITIAL_TRACK_FOR_MAP, Tracker::class.java)
        initialGroupForMap = savedInstanceState?.getParcelable(KEY_INITIAL_GROUP_FOR_MAP, Group::class.java)
        initialGroupZoomToTrackerId = savedInstanceState?.getString(KEY_INITIAL_GROUP_ZOOM_TRACKER_ID)
        groupContextForMap = savedInstanceState?.getParcelable(KEY_GROUP_CONTEXT_FOR_MAP, Group::class.java)
        groupMapOpenedFromTab = savedInstanceState?.getInt(KEY_GROUP_MAP_OPENED_FROM_TAB, -1) ?: -1

        // When launching on the Map tab, pre-fetch selected tracker so the map can zoom to its extent
        if (savedTab == 1) {
            val selectedTrackerId = SelectedTrackerPrefs.selectedTrackerId(this)
            if (selectedTrackerId.isNotEmpty()) {
                TrackerRepository.getTrackerFromCache(selectedTrackerId)?.let { cachedSelected ->
                    setInitialTrackForMap(cachedSelected)
                }
            }
        }
        runStartupRefresh(savedTab)

        viewPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                if (position != 1) groupContextForMap = null
                if (!isHandlingTabBack && lastSelectedTabIndex >= 0) {
                    tabBackStack.addLast(lastSelectedTabIndex)
                }
                lastSelectedTabIndex = position
                updateNavTabBackground(position)
            }
        })

        findViewById<View>(R.id.navHome).setOnClickListener { navigateToTabWithOverlayClear(0) }
        findViewById<View>(R.id.navMap).setOnClickListener { openMapTabFromBottomNav() }
        findViewById<View>(R.id.navTrackers).setOnClickListener { openTrackersTabFromBottomNav() }
        findViewById<View>(R.id.navShared).setOnClickListener { navigateToTabWithOverlayClear(3) }
        findViewById<View>(R.id.navSettings).setOnClickListener { navigateToTabWithOverlayClear(4) }

        updateNavTabBackground(savedTab)
        supportFragmentManager.addOnBackStackChangedListener { updateBottomNavForOverlay() }
        updateBottomNavForOverlay()
        updatePermissionsState()

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (supportFragmentManager.backStackEntryCount > 0) {
                        supportFragmentManager.popBackStack()
                    } else if (viewPager.currentItem == 1 && groupContextForMap != null) {
                        val group = groupContextForMap ?: return@handleOnBackPressed
                        val sourceTab = groupMapOpenedFromTab
                        groupContextForMap = null
                        groupMapOpenedFromTab = -1
                        if (sourceTab >= 0 && tabBackStack.isNotEmpty() && tabBackStack.last() == sourceTab) {
                            tabBackStack.removeLast()
                        }
                        val targetTab = if (sourceTab >= 0) sourceTab else 2
                        val fm = supportFragmentManager
                        fm.beginTransaction()
                            .add(R.id.fragment_overlay_container, com.geovault.tracker.fragments.GroupActionsFragment.newInstance(group), "group_actions")
                            .addToBackStack(null)
                            .commit()
                        fm.executePendingTransactions()
                        isHandlingTabBack = true
                        viewPager.setCurrentItem(targetTab, false)
                        isHandlingTabBack = false
                        if (targetTab == 2) {
                            viewPager.post {
                                (pagerAdapter.getFragment(2) as? com.geovault.tracker.fragments.TrackersPagerFragment)?.selectGroupsTab()
                            }
                        }
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
                Toast.makeText(this, getString(R.string.connected_as, email), Toast.LENGTH_SHORT).show()
            }
            intent.removeExtra(EXTRA_SIGNED_IN_EMAIL)
        }
        intent?.getStringExtra(EXTRA_OAUTH_ERROR)?.let { message ->
            rootView.post { showSnackbar(message) }
            intent.removeExtra(EXTRA_OAUTH_ERROR)
        }

        // Restart-if-killed and start-on-launch
        val settings = settingsRepository.getSettings()
        val restartIfKilled = settings.resetTrackingIfKilled
        val wasTrackingBeforeExit = settingsRepository.wasTrackingBeforeExit()
        if (!restartIfKilled || TrackingRuntimeStateStore.state.value.isRunning) {
            if (wasTrackingBeforeExit) {
                settingsRepository.clearWasTrackingBeforeExit()
            }
        } else if (wasTrackingBeforeExit) {
            tryResumeTrackingAfterKill()
            return
        }
        if (!TrackingRuntimeStateStore.state.value.isRunning && settings.startTrackingOnLaunch) {
            tryStartTrackingOnLaunch()
        }
    }

    private fun tryResumeTrackingAfterKill() {
        if (!ensureTrackingStartReadiness()) {
            settingsRepository.clearWasTrackingBeforeExit()
            return
        }
        val trackerId = SelectedTrackerPrefs.selectedTrackerId(this)
        if (trackerId.isEmpty()) {
            settingsRepository.clearWasTrackingBeforeExit()
            return
        }
        tryStartTrackingSilently(
            onInvalid = {
                SelectedTrackerManager.clearSelectedTrackerAndInvalidateCaches(this, clearTrackersListCache = true)
                settingsRepository.clearWasTrackingBeforeExit()
                lifecycleScope.launch {
                    val list = TrackerRepository.getTrackersSuspend(this@MainActivity, forceRefresh = true)
                    setServerAccessibility(list != null)
                }
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
        if (!ensureTrackingStartReadiness()) return
        val trackerId = SelectedTrackerPrefs.selectedTrackerId(this)
        if (trackerId.isEmpty()) return
        tryStartTrackingSilently(
            onInvalid = {
                SelectedTrackerManager.clearSelectedTrackerAndInvalidateCaches(this, clearTrackersListCache = true)
                lifecycleScope.launch {
                    val list = TrackerRepository.getTrackersSuspend(this@MainActivity, forceRefresh = true)
                    setServerAccessibility(list != null)
                }
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
        val trackerId = SelectedTrackerPrefs.selectedTrackerId(this)
        if (trackerId.isEmpty()) return
        lifecycleScope.launch {
            val valid = TrackerRepository.checkTrackerSuspend(this@MainActivity, trackerId)
            if (valid) onValid() else onInvalid()
        }
    }
    
    /** Pops all overlay fragments (hidden trackers, groups, params, etc.) so the tapped nav tab is visible. */
    private fun clearOverlayAndThen(action: () -> Unit) {
        while (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStackImmediate()
        }
        action()
    }

    private fun navigateToTabWithOverlayClear(tabIndex: Int) {
        clearOverlayAndThen { viewPager.setCurrentItem(tabIndex, false) }
    }

    private fun openTrackersTabFromBottomNav() {
        clearOverlayAndThen {
            if (viewPager.currentItem == 2) {
                viewPager.post {
                    (pagerAdapter.getFragment(2) as? com.geovault.tracker.fragments.TrackersPagerFragment)?.selectTrackersTab()
                }
            } else {
                viewPager.setCurrentItem(2, false)
            }
        }
    }

    private fun openMapTabFromBottomNav() {
        clearOverlayAndThen {
            val mapFragment = pagerAdapter.getFragment(1) as? com.geovault.tracker.fragments.map.MapFragment
            val isStreaming = mapFragment?.isShowingStreamedTrack() ?: false
            val selectedTrackerId = SelectedTrackerPrefs.selectedTrackerId(this)

            if (viewPager.currentItem != 1 && selectedTrackerId.isNotEmpty() && initialTrackForMap == null && !isStreaming) {
                TrackerRepository.getTrackerFromCache(selectedTrackerId)?.let { cachedSelected ->
                    setInitialTrackForMap(cachedSelected)
                }
                setCurrentTab(1, forceRefreshMap = true, delayMs = 0)
                lifecycleScope.launch {
                    val list = TrackerRepository.getTrackersSuspend(this@MainActivity, forceRefresh = false)
                    val selectedTracker = list?.find { it.id == selectedTrackerId }
                    if (selectedTracker != null) {
                        updateInitialTrackForMapIfPending(selectedTracker)
                    }
                }
            } else if (viewPager.currentItem != 1) {
                val forceRefresh = initialTrackForMap != null
                setCurrentTab(1, forceRefreshMap = forceRefresh, delayMs = 0)
            } else {
                viewPager.setCurrentItem(1, false)
            }
        }
    }

    private fun updateBottomNavForOverlay() {
        val navHome = findViewById<View>(R.id.navHome)
        val navMap = findViewById<View>(R.id.navMap)
        val navTrackers = findViewById<View>(R.id.navTrackers)
        val navShared = findViewById<View>(R.id.navShared)
        val navSettings = findViewById<View>(R.id.navSettings)
        val topName = if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.getBackStackEntryAt(supportFragmentManager.backStackEntryCount - 1).name
        } else null
        val editGroupNames = setOf("group_detail", "group_trackers_list", "add_group_trackers", "edit_shared_group")
        val disableNav = topName == "new_tracker" || topName == "edit_tracker" || topName == "edit_shared_tracker" || (topName != null && topName in editGroupNames)
        navHome.isEnabled = !disableNav
        navHome.isClickable = !disableNav
        navMap.isEnabled = !disableNav
        navMap.isClickable = !disableNav
        navTrackers.isEnabled = !disableNav
        navTrackers.isClickable = !disableNav
        navShared.isEnabled = !disableNav
        navShared.isClickable = !disableNav
        navSettings.isEnabled = !disableNav
        navSettings.isClickable = !disableNav
        if (disableNav) {
            val grayColor = ContextCompat.getColor(this, R.color.text_secondary)
            updateNavTabColors(navHome, false, grayColor, grayColor)
            updateNavTabColors(navMap, false, grayColor, grayColor)
            updateNavTabColors(navTrackers, false, grayColor, grayColor)
            updateNavTabColors(navShared, false, grayColor, grayColor)
            updateNavTabColors(navSettings, false, grayColor, grayColor)
        } else {
            updateNavTabBackground(viewPager.currentItem)
        }
    }

    private fun updateNavTabBackground(position: Int) {
        val navHome = findViewById<View>(R.id.navHome)
        val navMap = findViewById<View>(R.id.navMap)
        val navTrackers = findViewById<View>(R.id.navTrackers)
        val navShared = findViewById<View>(R.id.navShared)
        val navSettings = findViewById<View>(R.id.navSettings)

        val yellowColor = ContextCompat.getColor(this, R.color.warning_yellow)
        val whiteColor = ContextCompat.getColor(this, R.color.content_on_primary)
        val grayColor = ContextCompat.getColor(this, R.color.text_secondary)

        updateNavTabColors(navHome, position == 0, yellowColor, whiteColor)
        updateNavTabColors(navMap, position == 1, yellowColor, if (isServerAccessible) whiteColor else grayColor)
        updateNavTabColors(navTrackers, position == 2, yellowColor, if (isServerAccessible) whiteColor else grayColor)
        updateNavTabColors(navShared, position == 3, yellowColor, if (isServerAccessible) whiteColor else grayColor)
        updateNavTabColors(navSettings, position == 4, yellowColor, whiteColor)
    }

    private fun setServerAccessibility(accessible: Boolean) {
        if (isServerAccessible == accessible) return
        isServerAccessible = accessible
        
        val navMap = findViewById<View>(R.id.navMap)
        val navTrackers = findViewById<View>(R.id.navTrackers)
        val navShared = findViewById<View>(R.id.navShared)
        
        val grayColor = ContextCompat.getColor(this, R.color.text_secondary)
        val yellowColor = ContextCompat.getColor(this, R.color.warning_yellow)
        val whiteColor = ContextCompat.getColor(this, R.color.content_on_primary)
        
        if (!accessible) {
            // Disable Map, Trackers, and Shared tabs
            navMap.isEnabled = false
            navMap.isClickable = false
            navTrackers.isEnabled = false
            navTrackers.isClickable = false
            navShared.isEnabled = false
            navShared.isClickable = false
            
            updateNavTabColors(navMap, false, yellowColor, grayColor)
            updateNavTabColors(navTrackers, false, yellowColor, grayColor)
            updateNavTabColors(navShared, false, yellowColor, grayColor)
            
            // If we are on Map, Trackers, or Shared, switch to Home
            if (viewPager.currentItem == 1 || viewPager.currentItem == 2 || viewPager.currentItem == 3) {
                viewPager.setCurrentItem(0, false)
            }
        } else {
            // Re-enable Map, Trackers, and Shared tabs
            navMap.isEnabled = true
            navMap.isClickable = true
            navTrackers.isEnabled = true
            navTrackers.isClickable = true
            navShared.isEnabled = true
            navShared.isClickable = true
            
            updateNavTabBackground(viewPager.currentItem)
        }
        
        // Notify HomeFragment
        val homeFragment = pagerAdapter.getFragment(0) as? com.geovault.tracker.fragments.HomeFragment
        homeFragment?.updateServerAccessibilityUi(accessible)
    }
    
    private fun updateNavTabColors(navView: View, isSelected: Boolean, selectedColor: Int, defaultColor: Int) {
        val color = if (isSelected) selectedColor else defaultColor
        
        // Update icon color
        val icon = navView.findViewById<android.widget.ImageView>(
            when (navView.id) {
                R.id.navHome -> R.id.navHomeIcon
                R.id.navMap -> R.id.navMapIcon
                R.id.navTrackers -> R.id.navTrackersIcon
                R.id.navShared -> R.id.navSharedIcon
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
                R.id.navShared -> R.id.navSharedText
                R.id.navSettings -> R.id.navSettingsText
                else -> return
            }
        )
        text?.setTextColor(color)
    }

    /** Initial track (with latest 100 points) to show on map when opening from "View on map"; cleared after use. */
    var initialTrackForMap: Tracker? = null
        private set

    override fun setInitialTrackForMap(tracker: Tracker?) {
        initialTrackForMap = tracker
    }

    private fun updateInitialTrackForMapIfPending(tracker: Tracker) {
        val pending = initialTrackForMap ?: return
        if (pending.id == tracker.id) {
            initialTrackForMap = tracker
        }
    }

    override fun getAndClearInitialTrackForMap(): Tracker? {
        val t = initialTrackForMap
        initialTrackForMap = null
        return t
    }

    override val hasPendingInitialTrackForMap: Boolean
        get() = initialTrackForMap != null
    fun hasInitialTrackForMap(): Boolean = hasPendingInitialTrackForMap

    /** Initial group to fit on map when opening from "View group on map"; cleared after use. */
    var initialGroupForMap: Group? = null
        private set
    /** When opening map for a group, optional tracker id to zoom to (single item tap); null = fit entire group. */
    var initialGroupZoomToTrackerId: String? = null
        private set
    /** Group we're viewing on the map; back from map returns to group-actions overlay when this is set. Cleared when leaving map tab or after showing group actions. */
    private var groupContextForMap: Group? = null
    /** Tab index when [openMapForGroup] ran (before map), so back restores overlays on the correct tab. */
    private var groupMapOpenedFromTab: Int = -1

    fun setInitialGroupForMap(group: Group?, zoomToTrackerId: String? = null) {
        initialGroupForMap = group
        initialGroupZoomToTrackerId = zoomToTrackerId
    }

    /** Clears overlays (groups/actions/detail/etc.) and opens the map focused on this group. If [zoomToTrackerId] is set, camera fits that tracker only.
     * When [returnToTabOnly] is true (e.g. opened from group card popup menu), back from map returns to the originating tab without showing the group actions overlay. */
    override fun openMapForGroup(group: Group, zoomToTrackerId: String?, returnToTabOnly: Boolean) {
        groupMapOpenedFromTab = viewPager.currentItem
        setInitialGroupForMap(group, zoomToTrackerId)
        groupContextForMap = if (returnToTabOnly) null else group
        clearOverlayAndThen {
            setCurrentTab(1, forceRefreshMap = true, delayMs = 0)
        }
    }

    override fun openMapAllTrackers() {
        clearOverlayAndThen {
            setCurrentTab(1, forceRefreshMap = false, delayMs = 0)
            viewPager.post {
                (pagerAdapter.getFragment(1) as? com.geovault.tracker.fragments.map.MapFragment)
                    ?.showAllTrackersFromSettings()
            }
        }
    }

    /** Returns (group, zoomToTrackerId) and clears both. For use by MapFragment when consuming deferred group handoff in onMapReady. */
    override fun getAndClearInitialGroupAndZoomForMap(): Pair<Group?, String?> {
        return getAndClearInitialGroupAndZoomTo()
    }

    /** Returns (group, zoomToTrackerId) and clears both. Use when opening map for a group so zoom-to is passed. */
    private fun getAndClearInitialGroupAndZoomTo(): Pair<Group?, String?> {
        val g = initialGroupForMap
        val z = initialGroupZoomToTrackerId
        initialGroupForMap = null
        initialGroupZoomToTrackerId = null
        return Pair(g, z)
    }

    override fun setCurrentTab(index: Int, forceRefreshMap: Boolean, delayMs: Long) {
        if (forceRefreshMap && index == 1) {
            val mapFragment = pagerAdapter.getFragment(1) as? com.geovault.tracker.fragments.map.MapFragment
            if (mapFragment != null) {
                val (group, zoomToTrackerId) = getAndClearInitialGroupAndZoomTo()
                if (group != null) {
                    mapFragment.refreshMapForGroup(group, zoomToTrackerId)
                } else {
                    mapFragment.refreshTrackForSelectedTracker()
                }
            }
            // If fragment is not yet created, leave initialGroupForMap/initialGroupZoomToTrackerId
            // for MapFragment to consume in onMapReady (deferred handoff).
        }
        
        if (delayMs > 0) {
            Handler(Looper.getMainLooper()).postDelayed({
                viewPager.setCurrentItem(index, false)
            }, delayMs)
        } else {
            viewPager.setCurrentItem(index, false)
        }
    }

    /** Switch to the Trackers tab and scroll the list to the given tracker (e.g. when user taps the name chip on the map). */
    override fun openTrackersAndScrollTo(trackerId: String?) {
        viewPager.setCurrentItem(2, false)
        requestTrackersScrollWhenReady(trackerId)
    }

    /** Switch to the Shared tab and scroll the list to the given tracker or group (e.g. from map name chip or "View in list"). */
    override fun openSharedAndScrollTo(trackerId: String?, groupId: String?) {
        viewPager.setCurrentItem(3, false)
        requestSharedScrollWhenReady(trackerId, groupId)
    }

    private fun requestTrackersScrollWhenReady(trackerId: String?, attemptsLeft: Int = 8) {
        viewPager.post {
            val pagerFragment = pagerAdapter.getFragment(2) as? com.geovault.tracker.fragments.TrackersPagerFragment
            pagerFragment?.selectTrackersTab()
            val trackersList = pagerFragment?.getTrackersListFragment()
            if (trackersList != null) {
                trackersList.requestScrollToTrackerId(trackerId)
            } else if (attemptsLeft > 0) {
                Handler(Looper.getMainLooper()).postDelayed(
                    { requestTrackersScrollWhenReady(trackerId, attemptsLeft - 1) },
                    50
                )
            }
        }
    }

    private fun requestSharedScrollWhenReady(trackerId: String?, groupId: String?, attemptsLeft: Int = 8) {
        viewPager.post {
            val sharedFragment = pagerAdapter.getFragment(3) as? com.geovault.tracker.fragments.SharedTrackersFragment
            if (sharedFragment != null) {
                if (groupId != null) {
                    sharedFragment.requestScrollToGroupId(groupId)
                } else {
                    sharedFragment.requestScrollToTrackerId(trackerId)
                }
            } else if (attemptsLeft > 0) {
                Handler(Looper.getMainLooper()).postDelayed(
                    { requestSharedScrollWhenReady(trackerId, groupId, attemptsLeft - 1) },
                    50
                )
            }
        }
    }

    /** Show group members overlay and scroll/highlight the given tracker (e.g. from map tap "View in list" in group context). */
    override fun openGroupMembersAndScrollTo(group: Group, trackerId: String?) {
        supportFragmentManager.beginTransaction()
            .add(R.id.fragment_overlay_container, com.geovault.tracker.fragments.GroupActionsFragment.newInstance(group, trackerId), "group_actions")
            .addToBackStack(null)
            .commit()
    }

    override fun showNewTrackerFragment() {
        supportFragmentManager.beginTransaction()
            .add(R.id.fragment_overlay_container, com.geovault.tracker.fragments.NewTrackerFragment(), "new_tracker")
            .addToBackStack("new_tracker")
            .commit()
    }

    override fun showEditTrackerFragment(tracker: com.geovault.tracker.Tracker) {
        if (!tracker.isOwner()) {
            showEditSharedTrackerFragment(tracker)
            return
        }
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

    override fun showEditSharedTrackerFragment(tracker: com.geovault.tracker.Tracker) {
        val fragment = com.geovault.tracker.fragments.EditSharedTrackerFragment().apply {
            arguments = android.os.Bundle().apply {
                putParcelable(com.geovault.tracker.fragments.EditSharedTrackerFragment.ARG_TRACKER, tracker)
            }
        }
        supportFragmentManager.beginTransaction()
            .add(R.id.fragment_overlay_container, fragment, "edit_shared_tracker")
            .addToBackStack("edit_shared_tracker")
            .commit()
    }

    override fun showEditSharedGroupFragment(group: com.geovault.tracker.Group) {
        val fragment = com.geovault.tracker.fragments.EditSharedGroupFragment().apply {
            arguments = android.os.Bundle().apply {
                putParcelable(com.geovault.tracker.fragments.EditSharedGroupFragment.ARG_GROUP, group)
            }
        }
        supportFragmentManager.beginTransaction()
            .add(R.id.fragment_overlay_container, fragment, "edit_shared_group")
            .addToBackStack("edit_shared_group")
            .commit()
    }

    /** Switch to the Trackers bottom tab and select the Groups sub-tab. */
    override fun showGroupsFragment() {
        viewPager.setCurrentItem(2, false)
        viewPager.post {
            (pagerAdapter.getFragment(2) as? com.geovault.tracker.fragments.TrackersPagerFragment)?.selectGroupsTab()
        }
    }

    override fun showHiddenTrackersFragment() {
        supportFragmentManager.beginTransaction()
            .add(R.id.fragment_overlay_container, com.geovault.tracker.fragments.HiddenTrackersFragment(), "hidden_trackers")
            .addToBackStack("hidden_trackers")
            .commit()
    }

    override fun showTrackerParamsFragment(
        trackerId: String,
        trackerName: String?,
        lastUpdateMs: Long?,
        positionLat: Double?,
        positionLon: Double?
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
    override fun showSnackbar(message: String) {
        importantMessageSnackbar?.showMessage(message)
    }
    
    override fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
               ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    
    override fun hasBackgroundLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    
    override fun hasNotificationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    
    override fun hasBatteryOptimizationExemption(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }
    
    override fun hasAllRequiredPermissions(): Boolean {
        return hasLocationPermission() && hasBackgroundLocationPermission() && 
               hasNotificationPermission() && hasBatteryOptimizationExemption()
    }
    
    override fun requestLocationPermission() {
        locationPermissionLauncher.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ))
    }
    
    override fun requestBackgroundLocationPermission() {
        if (!hasLocationPermission()) {
            showSnackbar(getString(R.string.location_permission_needed_first))
            return
        }
        backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }
    
    override fun requestNotificationPermission() {
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    
    override fun requestBatteryOptimizationExemption() {
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

    override fun toggleTracking() {
        val homeFragment = pagerAdapter.getFragment(0) as? com.geovault.tracker.fragments.HomeFragment
        if (isPreparingToTrack) {
            showStopTrackingConfirmation {
                isPreparingToTrack = false
                homeFragment?.updateTrackingUi()
            }
            return
        }
        val intent = Intent(this, TrackingService::class.java)
        if (TrackingRuntimeStateStore.state.value.isRunning) {
            showStopTrackingConfirmation {
                settingsRepository.clearWasTrackingBeforeExit()
                intent.action = TrackingService.ACTION_STOP
                startService(intent)
                Handler(Looper.getMainLooper()).postDelayed({
                    homeFragment?.updateTrackingUi()
                }, 300)
            }
            return
        }
        val trackerId = SelectedTrackerPrefs.selectedTrackerId(this)
        if (trackerId.isEmpty()) {
            showSnackbar(getString(R.string.no_tracker_selected_go_to_settings))
            return
        }
        if (!ensureTrackingStartReadiness()) {
            return
        }
        isPreparingToTrack = true
        homeFragment?.showPreparingState()
        lifecycleScope.launch {
            val valid = TrackerRepository.checkTrackerSuspend(this@MainActivity, trackerId)
            if (!isPreparingToTrack) return@launch
            isPreparingToTrack = false
            if (!valid) {
                SelectedTrackerManager.clearSelectedTrackerAndInvalidateCaches(
                    this@MainActivity,
                    clearTrackersListCache = true
                )
                val list = TrackerRepository.getTrackersSuspend(this@MainActivity, forceRefresh = true)
                setServerAccessibility(list != null)
                showSnackbar(getString(R.string.tracker_validation_failed_go_to_settings))
                val hf = pagerAdapter.getFragment(0) as? com.geovault.tracker.fragments.HomeFragment
                hf?.updateTrackingUi()
                return@launch
            }
            intent.action = TrackingService.ACTION_START
            startForegroundService(intent)
            Handler(Looper.getMainLooper()).postDelayed({
                val hf = pagerAdapter.getFragment(0) as? com.geovault.tracker.fragments.HomeFragment
                hf?.updateTrackingUi()
            }, 300)
        }
    }

    override fun updateQueueCountFromFragment(textView: TextView) {
        lifecycleScope.launch {
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
        if (!trackingErrorReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                trackingErrorReceiver,
                IntentFilter(TrackingService.ACTION_TRACKING_ERROR),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            trackingErrorReceiverRegistered = true
        }
        if (!streamingErrorReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                streamingErrorReceiver,
                IntentFilter(LiveTrackStreamingService.ACTION_STREAMING_ERROR),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            streamingErrorReceiverRegistered = true
        }
        updatePermissionsState()
        if (TrackingRuntimeStateStore.state.value.isRunning && !hasLocationPermission()) {
            settingsRepository.clearWasTrackingBeforeExit()
            startService(Intent(this, TrackingService::class.java).apply {
                action = TrackingService.ACTION_STOP
            })
            showSnackbar(getString(R.string.location_permission_revoked))
        }
    }

    override fun onStop() {
        if (trackingErrorReceiverRegistered) {
            unregisterReceiver(trackingErrorReceiver)
            trackingErrorReceiverRegistered = false
        }
        if (streamingErrorReceiverRegistered) {
            unregisterReceiver(streamingErrorReceiver)
            streamingErrorReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations && LiveStreamRuntimeStateStore.state.value.isRunning) {
            startService(Intent(this, LiveTrackStreamingService::class.java).apply {
                action = LiveTrackStreamingService.ACTION_STOP
            })
        }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!isGuestView) {
            outState.putInt(KEY_CURRENT_TAB, viewPager.currentItem)
            outState.putIntegerArrayList(KEY_TAB_BACK_STACK, ArrayList(tabBackStack))
            outState.putParcelable(KEY_INITIAL_TRACK_FOR_MAP, initialTrackForMap)
            outState.putParcelable(KEY_INITIAL_GROUP_FOR_MAP, initialGroupForMap)
            outState.putString(KEY_INITIAL_GROUP_ZOOM_TRACKER_ID, initialGroupZoomToTrackerId)
            outState.putParcelable(KEY_GROUP_CONTEXT_FOR_MAP, groupContextForMap)
            outState.putInt(KEY_GROUP_MAP_OPENED_FROM_TAB, groupMapOpenedFromTab)
        }
    }

    companion object {
        private const val KEY_CURRENT_TAB = "current_tab"
        private const val KEY_TAB_BACK_STACK = "tab_back_stack"
        private const val KEY_INITIAL_TRACK_FOR_MAP = "initial_track_for_map"
        private const val KEY_INITIAL_GROUP_FOR_MAP = "initial_group_for_map"
        private const val KEY_INITIAL_GROUP_ZOOM_TRACKER_ID = "initial_group_zoom_tracker_id"
        private const val KEY_GROUP_CONTEXT_FOR_MAP = "group_context_for_map"
        private const val KEY_GROUP_MAP_OPENED_FROM_TAB = "group_map_opened_from_tab"
        const val EXTRA_SIGNED_IN_EMAIL = "signed_in_email"
        const val EXTRA_OAUTH_ERROR = "oauth_error"

        internal fun extractStreamingErrorMessage(intent: Intent?): String? {
            if (intent?.action != LiveTrackStreamingService.ACTION_STREAMING_ERROR) return null
            return intent.getStringExtra(LiveTrackStreamingService.EXTRA_STREAMING_ERROR_MESSAGE)
                ?.takeIf { it.isNotBlank() }
        }
    }

    private fun ensureTrackingStartReadiness(): Boolean {
        if (!hasLocationPermission()) {
            showSnackbar(getString(R.string.location_permission_needed_first))
            requestLocationPermission()
            return false
        }
        if (!hasBackgroundLocationPermission()) {
            showSnackbar(getString(R.string.background_location_permission_required))
            requestBackgroundLocationPermission()
            return false
        }
        if (!hasNotificationPermission()) {
            showSnackbar(getString(R.string.notification_permission_required))
            requestNotificationPermission()
            return false
        }
        if (!hasBatteryOptimizationExemption()) {
            showSnackbar(getString(R.string.battery_optimization_exemption_required))
            return false
        }
        return true
    }
}
