package com.geovault.tracker.ui

import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.geovault.common.auth.GeoVaultAccountUiState
import com.geovault.common.maps.core.rememberGeoVaultMainMap
import com.geovault.common.ui.GeoVaultAppShell
import com.geovault.common.ui.GeoVaultAppSnackbarLayer
import com.geovault.common.ui.GeoVaultAuthShellState
import com.geovault.common.ui.GeoVaultShellOverlayScaffold
import com.geovault.common.ui.components.GeoVaultBottomNavDestination
import com.geovault.common.ui.components.GeoVaultShellSettingsOverlayHost
import com.geovault.common.ui.navigation.GeoVaultRegisterBackHandler
import com.geovault.common.ui.snackbar.GeoVaultSnackbarModel
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
    onClearUpdateAvailable: () -> Unit = {},
    onRequestStartTracking: () -> Unit = {},
    onRequestStopTracking: () -> Unit = {},
    onRequestManualPoint: () -> Unit = {},
    settingsState: SettingsState,
    accountState: GeoVaultAccountUiState,
    onSettingsServerUrlChanged: (String) -> Unit,
    onSettingsConnect: () -> Unit,
    onSettingsDisconnect: () -> Unit,
    onSettingsLowAccuracyFallbackEnabled: (Boolean) -> Unit,
    onSettingsLowAccuracyTimeoutInput: (String) -> Unit,
    onSettingsStartOnBoot: (Boolean) -> Unit,
    onSettingsStartOnLaunch: (Boolean) -> Unit,
    onSettingsSendExtendedData: (Boolean) -> Unit,
    onSettingsSignificantMotionOnly: (Boolean) -> Unit,
    onSettingsSparseTracking: (Boolean) -> Unit,
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
    val connectTooltip = stringResource(R.string.tooltip_settings_connect)
    val auth = remember(
        state.isAuthenticated,
        state.serverUrl,
        state.isConnecting,
        connectTooltip,
        onAuthServerUrlChanged,
        onAuthConnect,
        openSettingsOverlay,
    ) {
        GeoVaultAuthShellState(
            isAuthenticated = state.isAuthenticated,
            serverUrl = state.serverUrl,
            onServerUrlChanged = onAuthServerUrlChanged,
            onConnect = onAuthConnect,
            onOpenSettings = openSettingsOverlay,
            isConnecting = state.isConnecting,
            connectButtonTooltip = connectTooltip,
        )
    }
    val globalInfoModel = state.infoMessage
        ?.takeIf { it.isNotBlank() }
        ?.let { message ->
            GeoVaultSnackbarModel(
                id = "tracker-global-${message.hashCode()}",
                message = message,
            )
        }

    CompositionLocalProvider(LocalTrackerParamsOverlay provides trackerParamsOverlay) {
        GeoVaultAppShell(
            destinations = bottomDestinations,
            selectedDestinationId = selectedTab,
            overlayNavBarChrome = isSettingsOpen,
            alwaysComposedTabIds = setOf(TrackerTab.MAP.name),
            prewarmTabIds = listOf(TrackerTab.TRACKERS.name, TrackerTab.SHARED.name),
            prewarmEnabled = state.isAuthenticated,
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
            overlay = {
                GeoVaultShellSettingsOverlayHost(
                    visible = isSettingsOpen,
                    onDismissRequest = { isSettingsOpen = false },
                    backPriority = TrackerBackPriorities.FULL_SCREEN_OVERLAY,
                ) {
                    GeoVaultShellOverlayScaffold(
                        title = stringResource(R.string.nav_settings),
                        onClose = { isSettingsOpen = false },
                        closeContentDescription = stringResource(R.string.close),
                    ) { padding ->
                        SettingsScreen(
                            state = settingsState,
                            accountState = accountState,
                            onServerUrlChanged = onSettingsServerUrlChanged,
                            onConnect = onSettingsConnect,
                            onDisconnect = onSettingsDisconnect,
                            onLowAccuracyFallbackEnabled = onSettingsLowAccuracyFallbackEnabled,
                            onLowAccuracyTimeoutInput = onSettingsLowAccuracyTimeoutInput,
                            onStartOnBoot = onSettingsStartOnBoot,
                            onStartOnLaunch = onSettingsStartOnLaunch,
                            onSendExtendedData = onSettingsSendExtendedData,
                            onSignificantMotionOnly = onSettingsSignificantMotionOnly,
                            onSparseTracking = onSettingsSparseTracking,
                            onKeepScreenOnMap = onSettingsKeepScreenOnMap,
                            onGroupModeFitOnlyActiveTrackers = onSettingsGroupModeFitOnlyActiveTrackers,
                            onRefreshHiddenTrackerItems = onSettingsRefreshHiddenTrackerItems,
                            onUnhideTrackerItem = onSettingsUnhideTrackerItem,
                            onUnhideAllTrackerItems = onSettingsUnhideAllTrackerItems,
                            onOpenAllTrackersOnMap = navigateToAllTrackersOnMap,
                            onClose = { isSettingsOpen = false },
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = padding,
                        )
                    }
                }
            },
            snackbarLayer = {
                GeoVaultAppSnackbarLayer(
                    snackbar = globalInfoModel,
                    onDismissSnackbar = onClearInfoMessage,
                    update = state.updateAvailable,
                    onDismissUpdate = onClearUpdateAvailable,
                )
            },
        ) { tabId, isActive ->
            CompositionLocalProvider(LocalTrackerTabIsActive provides isActive) {
                when (tabId) {
                    TrackerTab.MAP.name -> MapScreen(
                        map = trackerMainMap,
                        mapViewModel = mapViewModel,
                        modifier = Modifier.fillMaxSize(),
                        isActive = isActive,
                        auth = auth,
                        isServerAccessible = state.isServerAccessible,
                        onHostNavigationRequested = onMapHostNavigationRequested,
                        onRequestTrackerParams = { args -> trackerParamsArgs = args },
                    )
                    TrackerTab.HOME.name -> HomeScreen(
                        auth = auth,
                        isServerAccessible = state.isServerAccessible,
                        isPreparingToTrack = state.isPreparingToTrack,
                        infoMessage = state.infoMessage,
                        onClearInfoMessage = onClearInfoMessage,
                        onRequestStartTracking = onRequestStartTracking,
                        onRequestStopTracking = onRequestStopTracking,
                        onRequestManualPoint = onRequestManualPoint,
                        onRequestTrackerParams = { args -> trackerParamsArgs = args },
                    )
                    TrackerTab.TRACKERS.name -> TrackersScreen(
                        vm = trackersGroupsViewModel,
                        trackersTabBottomNavStamp = trackersTabBottomNavStamp,
                        auth = auth,
                        isServerAccessible = state.isServerAccessible,
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
                    TrackerTab.SHARED.name -> SharedScreen(
                        vm = sharedViewModel,
                        sharedTabBottomNavStamp = sharedTabBottomNavStamp,
                        auth = auth,
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
