package com.geovault.tracker.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Scaffold
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.People
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.geovault.common.maps.core.rememberGeoVaultMainMap
import com.geovault.common.ui.components.GeoVaultBottomNavDestination
import com.geovault.common.ui.components.GeoVaultBottomNavScaffold
import com.geovault.common.ui.components.GeoVaultShellSettingsOverlayHost
import com.geovault.common.ui.components.GeoVaultSubViewHostActiveProvider
import com.geovault.common.ui.components.GeoVaultTopTitleBar
import com.geovault.common.ui.navigation.GeoVaultRegisterBackHandler
import com.geovault.common.ui.snackbar.GeoVaultSnackbarModel
import com.geovault.common.ui.snackbar.GeoVaultSnackbarHost
import com.geovault.common.ui.update.GeoVaultUpdateAvailableSnackbarHost
import com.geovault.tracker.presentation.HiddenTrackerItem
import com.geovault.tracker.presentation.MainScreenViewModel
import com.geovault.tracker.presentation.MainScreenState
import com.geovault.tracker.presentation.TrackerMapDisplayMode
import com.geovault.tracker.presentation.SettingsState
import com.geovault.tracker.presentation.SharedSubTab
import com.geovault.tracker.presentation.SharedViewModel
import com.geovault.tracker.presentation.TrackerMapViewModel
import com.geovault.tracker.presentation.TrackersGroupsSubTab
import com.geovault.tracker.presentation.TrackersGroupsViewModel
import com.geovault.tracker.settings.TrackerTrackingProfile
import com.geovault.tracker.params.TrackerParamsRouteArgs
import com.geovault.tracker.R
import com.geovault.tracker.TrackerApplication

@Composable
fun MainScreen(
    mainScreenViewModel: MainScreenViewModel,
    state: MainScreenState,
    mapRecoveryRequestToken: Long = 0L,
    onMapRecoveryRequestConsumed: (Long) -> Unit = {},
    onAuthServerUrlChanged: (String) -> Unit,
    onAuthConnect: () -> Unit,
    onClearInfoMessage: () -> Unit = {},
    onClearUpdatePrompt: () -> Unit = {},
    onRequestStartTracking: () -> Unit = {},
    onRequestStopTracking: () -> Unit = {},
    onRequestManualPoint: () -> Unit = {},
    settingsState: SettingsState,
    onSettingsServerUrlChanged: (String) -> Unit,
    onSettingsConnect: () -> Unit,
    onSettingsDisconnect: () -> Unit,
    onSettingsTrackingProfileSelected: (TrackerTrackingProfile) -> Unit,
    onSettingsLoggingIntervalInput: (String) -> Unit,
    onSettingsDistanceFilterInput: (String) -> Unit,
    onSettingsAccuracyFilterInput: (String) -> Unit,
    onSettingsLowAccuracyFallbackEnabled: (Boolean) -> Unit,
    onSettingsLowAccuracyTimeoutInput: (String) -> Unit,
    onSettingsStartOnBoot: (Boolean) -> Unit,
    onSettingsStartOnLaunch: (Boolean) -> Unit,
    onSettingsSendExtendedData: (Boolean) -> Unit,
    onSettingsSignificantMotionOnly: (Boolean) -> Unit,
    onSettingsAutoTrackingMode: (Boolean) -> Unit,
    onSettingsKeepScreenOnMap: (Boolean) -> Unit,
    onSettingsGroupModeFitOnlyActiveTrackers: (Boolean) -> Unit,
    onSettingsRefreshHiddenTrackerItems: () -> Unit,
    onSettingsUnhideTrackerItem: (HiddenTrackerItem) -> Unit,
    onSettingsUnhideAllTrackerItems: () -> Unit,
) {
    val trackerMainMap = rememberGeoVaultMainMap(TrackerApplication.TRACKER_MAIN_MAP_KEY)
    val mapViewModel: TrackerMapViewModel = viewModel()
    val pendingOpenAllTrackersOnMap by mainScreenViewModel.pendingOpenAllTrackersOnMap.collectAsState()
    val trackersGroupsViewModel: TrackersGroupsViewModel = viewModel()
    val sharedViewModel: SharedViewModel = viewModel()
    var selectedTab by rememberSaveable { mutableStateOf(TrackerTab.HOME.name) }
    var tabBackStack by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var isSettingsOpen by rememberSaveable { mutableStateOf(false) }
    var isHandlingTabBack by remember { mutableStateOf(false) }
    var pendingTrackersRequest by remember { mutableStateOf<TrackersHostNavigationRequest?>(null) }
    var pendingSharedRequest by remember { mutableStateOf<SharedHostNavigationRequest?>(null) }
    var trackersTabBottomNavStamp by remember { mutableIntStateOf(0) }
    var sharedTabBottomNavStamp by remember { mutableIntStateOf(0) }
    var pendingMapReturnContext by remember { mutableStateOf<MapReturnContext?>(null) }
    var trackerParamsArgs by remember { mutableStateOf<TrackerParamsRouteArgs?>(null) }

    // One-shot host navigation is only meaningful while that tab is composed; if the user
    // switches away before the child consumes it, drop the stale handoff (mirrors clearing
    // shell stacks when leaving a tab in Survey).
    LaunchedEffect(selectedTab) {
        if (selectedTab != TrackerTab.TRACKERS.name) {
            pendingTrackersRequest = null
        }
        if (selectedTab != TrackerTab.SHARED.name) {
            pendingSharedRequest = null
        }
        trackerParamsArgs = null
    }

    LaunchedEffect(state.isAuthenticated) {
        if (state.isAuthenticated) {
            trackersGroupsViewModel.beginShellBootstrapUi()
            sharedViewModel.beginShellBootstrapUi()
            val outcome = mainScreenViewModel.runAuthenticatedLaunchBootstrap()
            trackersGroupsViewModel.completeShellBootstrapUi(outcome)
            sharedViewModel.completeShellBootstrapUi(outcome)
        } else {
            trackersGroupsViewModel.resetSurfacePreloadAfterSignOut()
            sharedViewModel.resetSurfacePreloadAfterSignOut()
        }
    }
    val openTrackerOnMap = remember {
        { trackerId: String, trackerName: String?, source: MapReturnSource ->
            if (trackerId.isBlank()) return@remember
            mapViewModel.openTrackerOnMap(trackerId = trackerId, trackerName = trackerName)
            pendingMapReturnContext = when (source) {
                MapReturnSource.TRACKERS -> MapReturnContext(
                    tab = TrackerTab.TRACKERS,
                    trackersRequest = TrackersHostNavigationRequest(
                        subTab = TrackersGroupsSubTab.TRACKERS,
                        trackerId = trackerId,
                        focus = MapHostNavigationFocus.NONE,
                    )
                )
                MapReturnSource.SHARED -> MapReturnContext(
                    tab = TrackerTab.SHARED,
                    sharedRequest = SharedHostNavigationRequest(
                        subTab = SharedSubTab.SHARED,
                        trackerId = trackerId,
                        focus = MapHostNavigationFocus.NONE,
                    )
                )
            }
            selectedTab = TrackerTab.MAP.name
        }
    }
    val openGroupOnMap = remember {
        { groupId: String, source: MapReturnSource ->
            if (groupId.isBlank()) return@remember
            mapViewModel.openGroupOnMap(groupId)
            pendingMapReturnContext = when (source) {
                MapReturnSource.TRACKERS -> MapReturnContext(
                    tab = TrackerTab.TRACKERS,
                    trackersRequest = TrackersHostNavigationRequest(
                        subTab = TrackersGroupsSubTab.GROUPS,
                        groupId = groupId,
                        focus = MapHostNavigationFocus.NONE,
                    )
                )
                MapReturnSource.SHARED -> MapReturnContext(
                    tab = TrackerTab.SHARED,
                    sharedRequest = SharedHostNavigationRequest(
                        subTab = SharedSubTab.SHARED,
                        groupId = groupId,
                        focus = MapHostNavigationFocus.NONE,
                    )
                )
            }
            selectedTab = TrackerTab.MAP.name
        }
    }
    val navigateToAllTrackersOnMap: () -> Unit = {
        if (!isHandlingTabBack &&
            selectedTab.isNotBlank() &&
            selectedTab != TrackerTab.MAP.name
        ) {
            tabBackStack = (tabBackStack + selectedTab).takeLast(16)
        }
        pendingMapReturnContext = null
        mapViewModel.setMode(TrackerMapDisplayMode.ALL_QUEUE)
        isSettingsOpen = false
        selectedTab = TrackerTab.MAP.name
    }
    LaunchedEffect(pendingOpenAllTrackersOnMap) {
        if (!pendingOpenAllTrackersOnMap) return@LaunchedEffect
        mainScreenViewModel.consumePendingOpenAllTrackersOnMap()
        navigateToAllTrackersOnMap()
    }
    GeoVaultRegisterBackHandler(
        enabled = true,
        priority = TrackerBackPriorities.ROOT_MAP_RETURN,
        canGoBack = { selectedTab == TrackerTab.MAP.name && pendingMapReturnContext != null },
        onBack = {
            val context = pendingMapReturnContext ?: return@GeoVaultRegisterBackHandler false
            pendingMapReturnContext = null
            pendingTrackersRequest = context.trackersRequest
            pendingSharedRequest = context.sharedRequest
            isSettingsOpen = false
            selectedTab = context.tab.name
            true
        },
    )
    GeoVaultRegisterBackHandler(
        enabled = true,
        priority = TrackerBackPriorities.ROOT_TAB_BACK,
        canGoBack = {
            selectedTab != TrackerTab.HOME.name &&
                tabBackStack.isNotEmpty() &&
                !(selectedTab == TrackerTab.MAP.name && pendingMapReturnContext != null)
        },
        onBack = {
            val previous = tabBackStack.lastOrNull() ?: return@GeoVaultRegisterBackHandler false
            isHandlingTabBack = true
            tabBackStack = tabBackStack.dropLast(1)
            pendingMapReturnContext = null
            isSettingsOpen = false
            selectedTab = previous
            isHandlingTabBack = false
            true
        },
    )
    val openSettingsOverlay: () -> Unit = { isSettingsOpen = true }
    val onMapHostNavigationRequested = remember {
        { request: MapHostNavigationRequest ->
            if (!isHandlingTabBack && selectedTab.isNotBlank()) {
                tabBackStack = (tabBackStack + selectedTab).takeLast(16)
            }
            // Leaving map via host navigation is an explicit context switch.
            pendingMapReturnContext = null
            isSettingsOpen = false
            when (request.target) {
                MapHostNavigationTarget.TRACKERS -> {
                    selectedTab = TrackerTab.TRACKERS.name
                    pendingTrackersRequest = request.toTrackersHostNavigationRequest()
                }
                MapHostNavigationTarget.GROUPS -> {
                    selectedTab = TrackerTab.TRACKERS.name
                    pendingTrackersRequest = request.toTrackersHostNavigationRequest()
                }
                MapHostNavigationTarget.SHARED -> {
                    selectedTab = TrackerTab.SHARED.name
                    pendingSharedRequest = request.toSharedHostNavigationRequest()
                }
            }
        }
    }
    LaunchedEffect(state.isServerAccessible) {
        if (!state.isServerAccessible && selectedTab != TrackerTab.HOME.name) {
            isSettingsOpen = false
            selectedTab = TrackerTab.HOME.name
        }
    }
    LaunchedEffect(mapRecoveryRequestToken) {
        if (mapRecoveryRequestToken <= 0L) return@LaunchedEffect
        mapViewModel.restoreSelectedTrackerAfterStreamingStop()
        onMapRecoveryRequestConsumed(mapRecoveryRequestToken)
    }
    val bottomDestinations = listOf(
        GeoVaultBottomNavDestination(
            id = TrackerTab.HOME.name,
            label = stringResource(R.string.nav_home),
            icon = Icons.Default.Home,
            tooltip = stringResource(R.string.tooltip_nav_home),
        ),
        GeoVaultBottomNavDestination(
            id = TrackerTab.MAP.name,
            label = stringResource(R.string.nav_map),
            icon = Icons.Default.Map,
            enabled = state.isServerAccessible,
            tooltip = stringResource(R.string.tooltip_nav_map),
        ),
        GeoVaultBottomNavDestination(
            id = TrackerTab.TRACKERS.name,
            label = stringResource(R.string.nav_trackers),
            icon = Icons.AutoMirrored.Filled.List,
            enabled = state.isServerAccessible,
            tooltip = stringResource(R.string.tooltip_nav_trackers),
        ),
        GeoVaultBottomNavDestination(
            id = TrackerTab.SHARED.name,
            label = stringResource(R.string.nav_shared),
            icon = Icons.Default.People,
            enabled = state.isServerAccessible,
            tooltip = stringResource(R.string.tooltip_nav_shared),
        ),
    )

    val trackerParamsOverlay = trackerParamsArgs?.let { args ->
        TrackerParamsOverlayState(
            args = args,
            onDismiss = { trackerParamsArgs = null },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalTrackerParamsOverlay provides trackerParamsOverlay) {
            GeoVaultBottomNavScaffold(
                destinations = bottomDestinations,
                selectedDestinationId = selectedTab,
                onDestinationSelected = { destination ->
                    if (destination.id != selectedTab) {
                        if (!isHandlingTabBack && selectedTab.isNotBlank()) {
                            tabBackStack = (tabBackStack + selectedTab).takeLast(16)
                        }
                        if (destination.id != TrackerTab.MAP.name) {
                            pendingMapReturnContext = null
                        }
                        isSettingsOpen = false
                        selectedTab = destination.id
                        if (destination.id == TrackerTab.TRACKERS.name) {
                            pendingTrackersRequest = TrackersHostNavigationRequest(
                                subTab = TrackersGroupsSubTab.TRACKERS,
                            )
                            trackersTabBottomNavStamp++
                        }
                        if (destination.id == TrackerTab.SHARED.name) {
                            sharedViewModel.showSharedList()
                            sharedTabBottomNavStamp++
                        }
                    } else if (destination.id == TrackerTab.TRACKERS.name) {
                        pendingMapReturnContext = null
                        isSettingsOpen = false
                        pendingTrackersRequest = TrackersHostNavigationRequest(
                            subTab = TrackersGroupsSubTab.TRACKERS,
                        )
                        trackersTabBottomNavStamp++
                    } else if (destination.id == TrackerTab.SHARED.name) {
                        pendingMapReturnContext = null
                        isSettingsOpen = false
                        sharedViewModel.showSharedList()
                        sharedTabBottomNavStamp++
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) { _ ->
                // Keep visited tabs in composition so re-tapping a tab is instantaneous instead
                // of paying the (heavy) composition cost every time. We start with only the
                // initially-selected tab composed; each tap admits a tab into the visited set
                // and from then on it stays composed. zIndex puts the currently-active tab on
                // top for input routing; alpha hides the inactive ones.
                var visitedTabs by remember { mutableStateOf(setOf(selectedTab)) }
                LaunchedEffect(selectedTab) {
                    if (selectedTab !in visitedTabs) {
                        visitedTabs = visitedTabs + selectedTab
                    }
                }
                val composedTabs = visitedTabs + selectedTab
                // Pre-warm the Trackers and Shared tabs after the initial frame so that the
                // first tap on them is also instantaneous. We wait a bit so the visible tab's
                // first frame can land before we pay extra composition cost.
                LaunchedEffect(state.isAuthenticated) {
                    if (!state.isAuthenticated) return@LaunchedEffect
                    kotlinx.coroutines.delay(1200L)
                    val toAdd = listOf(TrackerTab.TRACKERS.name, TrackerTab.SHARED.name)
                        .filterNot { it in visitedTabs }
                    if (toAdd.isNotEmpty()) {
                        visitedTabs = visitedTabs + toAdd
                    }
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    val mapActive = selectedTab == TrackerTab.MAP.name && !isSettingsOpen
                    GeoVaultSubViewHostActiveProvider(isActive = mapActive) {
                    CompositionLocalProvider(LocalTrackerTabIsActive provides mapActive) {
                        MapScreen(
                            map = trackerMainMap,
                            mapViewModel = mapViewModel,
                            modifier = Modifier
                                .fillMaxSize()
                                .alpha(if (mapActive) 1f else 0f)
                                .zIndex(if (mapActive) 1f else 0f),
                            isActive = mapActive,
                            isAuthenticated = state.isAuthenticated,
                            serverUrl = state.serverUrl,
                            onAuthServerUrlChanged = onAuthServerUrlChanged,
                            onAuthConnect = onAuthConnect,
                            isConnecting = state.isConnecting,
                            onOpenSettings = openSettingsOverlay,
                            onHostNavigationRequested = onMapHostNavigationRequested,
                            onRequestTrackerParams = { args -> trackerParamsArgs = args },
                        )
                    }
                    }
                    if (TrackerTab.HOME.name in composedTabs) {
                        val homeActive = selectedTab == TrackerTab.HOME.name && !isSettingsOpen
                        GeoVaultSubViewHostActiveProvider(isActive = homeActive) {
                        CompositionLocalProvider(LocalTrackerTabIsActive provides homeActive) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .alpha(if (homeActive) 1f else 0f)
                                .zIndex(if (homeActive) 1f else 0f),
                        ) {
                            HomeScreen(
                                isAuthenticated = state.isAuthenticated,
                                isServerAccessible = state.isServerAccessible,
                                isPreparingToTrack = state.isPreparingToTrack,
                                serverUrl = state.serverUrl,
                                onAuthServerUrlChanged = onAuthServerUrlChanged,
                                onAuthConnect = onAuthConnect,
                                isConnecting = state.isConnecting,
                                onOpenSettings = openSettingsOverlay,
                                infoMessage = state.infoMessage,
                                onClearInfoMessage = onClearInfoMessage,
                                onRequestStartTracking = onRequestStartTracking,
                                onRequestStopTracking = onRequestStopTracking,
                                onRequestManualPoint = onRequestManualPoint,
                                onRequestTrackerParams = { args -> trackerParamsArgs = args },
                            )
                        }
                        }
                        }
                    }
                    if (TrackerTab.TRACKERS.name in composedTabs) {
                        val trackersActive = selectedTab == TrackerTab.TRACKERS.name && !isSettingsOpen
                        GeoVaultSubViewHostActiveProvider(isActive = trackersActive) {
                        CompositionLocalProvider(LocalTrackerTabIsActive provides trackersActive) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .alpha(if (trackersActive) 1f else 0f)
                                .zIndex(if (trackersActive) 1f else 0f),
                        ) {
                            TrackersScreen(
                                vm = trackersGroupsViewModel,
                                trackersTabBottomNavStamp = trackersTabBottomNavStamp,
                                isAuthenticated = state.isAuthenticated,
                                serverUrl = state.serverUrl,
                                onAuthServerUrlChanged = onAuthServerUrlChanged,
                                onAuthConnect = onAuthConnect,
                                isConnecting = state.isConnecting,
                                isServerAccessible = state.isServerAccessible,
                                onOpenSettings = openSettingsOverlay,
                                navigationRequest = pendingTrackersRequest,
                                onNavigationTargetConsumed = { pendingTrackersRequest = null },
                                onOpenTrackerOnMap = { trackerId, trackerName ->
                                    openTrackerOnMap(trackerId, trackerName, MapReturnSource.TRACKERS)
                                },
                                onOpenGroupOnMap = { groupId ->
                                    openGroupOnMap(groupId, MapReturnSource.TRACKERS)
                                },
                                onRequestTrackerParams = { args -> trackerParamsArgs = args },
                                onOpenSharedListToTracker = { trackerId ->
                                    selectedTab = TrackerTab.SHARED.name
                                    sharedViewModel.showSharedList()
                                    pendingSharedRequest = SharedHostNavigationRequest(
                                        subTab = SharedSubTab.SHARED,
                                        trackerId = trackerId,
                                        focus = MapHostNavigationFocus.SCROLL_TO_ITEM,
                                    )
                                },
                            )
                        }
                        }
                        }
                    }
                    if (TrackerTab.SHARED.name in composedTabs) {
                        val sharedActive = selectedTab == TrackerTab.SHARED.name && !isSettingsOpen
                        GeoVaultSubViewHostActiveProvider(isActive = sharedActive) {
                        CompositionLocalProvider(LocalTrackerTabIsActive provides sharedActive) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .alpha(if (sharedActive) 1f else 0f)
                                .zIndex(if (sharedActive) 1f else 0f),
                        ) {
                            SharedScreen(
                                vm = sharedViewModel,
                                sharedTabBottomNavStamp = sharedTabBottomNavStamp,
                                isAuthenticated = state.isAuthenticated,
                                serverUrl = state.serverUrl,
                                onAuthServerUrlChanged = onAuthServerUrlChanged,
                                onAuthConnect = onAuthConnect,
                                isConnecting = state.isConnecting,
                                onOpenSettings = openSettingsOverlay,
                                navigationRequest = pendingSharedRequest,
                                onNavigationTargetConsumed = { pendingSharedRequest = null },
                                onOpenTrackerOnMap = { trackerId, trackerName ->
                                    openTrackerOnMap(trackerId, trackerName, MapReturnSource.SHARED)
                                },
                                onOpenGroupOnMap = { groupId ->
                                    openGroupOnMap(groupId, MapReturnSource.SHARED)
                                },
                                onRequestTrackerParams = { args -> trackerParamsArgs = args },
                            )
                        }
                        }
                        }
                    }
                }
            }
        }
        GeoVaultShellSettingsOverlayHost(
            visible = isSettingsOpen,
            onDismissRequest = { isSettingsOpen = false },
            backPriority = TrackerBackPriorities.FULL_SCREEN_OVERLAY,
        ) {
            Scaffold(
                topBar = {
                    GeoVaultTopTitleBar(
                        title = stringResource(R.string.home_title),
                    )
                },
            ) { padding ->
                SettingsScreen(
                    state = settingsState,
                    onServerUrlChanged = onSettingsServerUrlChanged,
                    onConnect = onSettingsConnect,
                    onDisconnect = onSettingsDisconnect,
                    onTrackingProfileSelected = onSettingsTrackingProfileSelected,
                    onLoggingIntervalInput = onSettingsLoggingIntervalInput,
                    onDistanceFilterInput = onSettingsDistanceFilterInput,
                    onAccuracyFilterInput = onSettingsAccuracyFilterInput,
                    onLowAccuracyFallbackEnabled = onSettingsLowAccuracyFallbackEnabled,
                    onLowAccuracyTimeoutInput = onSettingsLowAccuracyTimeoutInput,
                    onStartOnBoot = onSettingsStartOnBoot,
                    onStartOnLaunch = onSettingsStartOnLaunch,
                    onSendExtendedData = onSettingsSendExtendedData,
                    onSignificantMotionOnly = onSettingsSignificantMotionOnly,
                    onAutoTrackingMode = onSettingsAutoTrackingMode,
                    onKeepScreenOnMap = onSettingsKeepScreenOnMap,
                    onGroupModeFitOnlyActiveTrackers = onSettingsGroupModeFitOnlyActiveTrackers,
                    onRefreshHiddenTrackerItems = onSettingsRefreshHiddenTrackerItems,
                    onUnhideTrackerItem = onSettingsUnhideTrackerItem,
                    onUnhideAllTrackerItems = onSettingsUnhideAllTrackerItems,
                    onOpenAllTrackersOnMap = navigateToAllTrackersOnMap,
                    onClose = { isSettingsOpen = false },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )
            }
        }
        val globalInfoModel = state.infoMessage
            ?.takeIf { it.isNotBlank() }
            ?.let { message ->
                GeoVaultSnackbarModel(
                    id = "tracker-global-${message.hashCode()}",
                    message = message,
                )
            }
        if (globalInfoModel != null) {
            GeoVaultSnackbarHost(
                model = globalInfoModel,
                onDismiss = onClearInfoMessage,
                onAction = { },
            )
        }
        GeoVaultUpdateAvailableSnackbarHost(
            model = state.updatePrompt,
            releaseUrl = state.updateReleaseUrl,
            onDismiss = onClearUpdatePrompt,
            stackBottomInset = if (globalInfoModel != null) 72.dp else 0.dp,
        )
    }
}

private enum class MapReturnSource {
    TRACKERS,
    SHARED,
}

private data class MapReturnContext(
    val tab: TrackerTab,
    val trackersRequest: TrackersHostNavigationRequest? = null,
    val sharedRequest: SharedHostNavigationRequest? = null,
)

private enum class TrackerTab {
    HOME,
    MAP,
    TRACKERS,
    SHARED,
}
