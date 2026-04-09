package com.geovault.tracker.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.geovault.common.maps.core.rememberGeoVaultMainMap
import com.geovault.common.ui.components.GeoVaultBottomNavDestination
import com.geovault.common.ui.components.GeoVaultBottomNavScaffold
import com.geovault.common.ui.navigation.GeoVaultRegisterBackHandler
import com.geovault.common.ui.snackbar.GeoVaultSnackbarModel
import com.geovault.common.ui.snackbar.GeoVaultSnackbarHost
import com.geovault.common.update.CustomTabReleasePageLauncher
import com.geovault.common.update.UpdateAvailablePromptComposer
import com.geovault.tracker.presentation.HiddenTrackerItem
import com.geovault.tracker.presentation.MainScreenState
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
    onSettingsRefreshHiddenTrackerItems: () -> Unit,
    onSettingsUnhideTrackerItem: (HiddenTrackerItem) -> Unit,
    onSettingsUnhideAllTrackerItems: () -> Unit,
) {
    val context = LocalContext.current
    val releaseLauncher = remember(context) { CustomTabReleasePageLauncher(context) }
    val trackerMainMap = rememberGeoVaultMainMap(TrackerApplication.TRACKER_MAIN_MAP_KEY)
    val mapViewModel: TrackerMapViewModel = viewModel()
    val trackersGroupsViewModel: TrackersGroupsViewModel = viewModel()
    val sharedViewModel: SharedViewModel = viewModel()
    var selectedTab by rememberSaveable { mutableStateOf(TrackerTab.HOME.name) }
    var lastSelectedTab by rememberSaveable { mutableStateOf("") }
    var tabBackStack by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var isHandlingTabBack by remember { mutableStateOf(false) }
    var pendingTrackersRequest by remember { mutableStateOf<TrackersHostNavigationRequest?>(null) }
    var pendingSharedRequest by remember { mutableStateOf<SharedHostNavigationRequest?>(null) }
    var pendingMapReturnContext by remember { mutableStateOf<MapReturnContext?>(null) }
    var trackerParamsArgs by remember { mutableStateOf<TrackerParamsRouteArgs?>(null) }
    LaunchedEffect(state.isAuthenticated) {
        if (state.isAuthenticated) {
            // Preload tab data that should be instant on first open.
            trackersGroupsViewModel.preloadTrackersSurface()
            sharedViewModel.preloadSharedSurface()
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
    GeoVaultRegisterBackHandler(
        enabled = true,
        priority = TrackerBackPriorities.ROOT_MAP_RETURN,
        canGoBack = { selectedTab == TrackerTab.MAP.name && pendingMapReturnContext != null },
        onBack = {
            val context = pendingMapReturnContext ?: return@GeoVaultRegisterBackHandler false
            pendingMapReturnContext = null
            pendingTrackersRequest = context.trackersRequest
            pendingSharedRequest = context.sharedRequest
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
            selectedTab = previous
            isHandlingTabBack = false
            true
        },
    )
    val openSettingsTab = remember {
        {
            if (!isHandlingTabBack &&
                selectedTab.isNotBlank() &&
                selectedTab != TrackerTab.SETTINGS.name
            ) {
                tabBackStack = (tabBackStack + selectedTab).takeLast(16)
            }
            selectedTab = TrackerTab.SETTINGS.name
        }
    }
    val onMapHostNavigationRequested = remember {
        { request: MapHostNavigationRequest ->
            if (!isHandlingTabBack && selectedTab.isNotBlank()) {
                tabBackStack = (tabBackStack + selectedTab).takeLast(16)
            }
            // Leaving map via host navigation is an explicit context switch.
            pendingMapReturnContext = null
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
            selectedTab = TrackerTab.HOME.name
        }
    }
    LaunchedEffect(selectedTab) {
        if (selectedTab == TrackerTab.MAP.name && lastSelectedTab != TrackerTab.MAP.name) {
            mapViewModel.markPendingInitialTrackerForMap()
        }
        lastSelectedTab = selectedTab
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
        ),
        GeoVaultBottomNavDestination(
            id = TrackerTab.MAP.name,
            label = stringResource(R.string.nav_map),
            icon = Icons.Default.Map,
            enabled = state.isServerAccessible,
        ),
        GeoVaultBottomNavDestination(
            id = TrackerTab.TRACKERS.name,
            label = stringResource(R.string.nav_trackers),
            icon = Icons.AutoMirrored.Filled.List,
            enabled = state.isServerAccessible,
        ),
        GeoVaultBottomNavDestination(
            id = TrackerTab.SHARED.name,
            label = stringResource(R.string.nav_shared),
            icon = Icons.Default.People,
            enabled = state.isServerAccessible,
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
                        selectedTab = destination.id
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) { _ ->
                Box(modifier = Modifier.fillMaxSize()) {
                    MapScreen(
                        map = trackerMainMap,
                        mapViewModel = mapViewModel,
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(if (selectedTab == TrackerTab.MAP.name) 1f else 0f),
                        isActive = selectedTab == TrackerTab.MAP.name,
                        isAuthenticated = state.isAuthenticated,
                        serverUrl = state.serverUrl,
                        onAuthServerUrlChanged = onAuthServerUrlChanged,
                        onAuthConnect = onAuthConnect,
                        isConnecting = state.isConnecting,
                        onOpenSettings = openSettingsTab,
                        onHostNavigationRequested = onMapHostNavigationRequested,
                        onRequestTrackerParams = { args -> trackerParamsArgs = args },
                    )
                    when (selectedTab) {
                    TrackerTab.HOME.name -> {
                    HomeScreen(
                        isAuthenticated = state.isAuthenticated,
                        isServerAccessible = state.isServerAccessible,
                        isPreparingToTrack = state.isPreparingToTrack,
                        serverUrl = state.serverUrl,
                        onAuthServerUrlChanged = onAuthServerUrlChanged,
                        onAuthConnect = onAuthConnect,
                        isConnecting = state.isConnecting,
                        onOpenSettings = openSettingsTab,
                        infoMessage = state.infoMessage,
                        onClearInfoMessage = onClearInfoMessage,
                        onRequestStartTracking = onRequestStartTracking,
                        onRequestStopTracking = onRequestStopTracking,
                        onRequestManualPoint = onRequestManualPoint,
                        onRequestTrackerParams = { args -> trackerParamsArgs = args },
                    )
                    }
                    TrackerTab.MAP.name -> Unit

                    TrackerTab.TRACKERS.name -> {
                    TrackersScreen(
                        vm = trackersGroupsViewModel,
                        isAuthenticated = state.isAuthenticated,
                        serverUrl = state.serverUrl,
                        onAuthServerUrlChanged = onAuthServerUrlChanged,
                        onAuthConnect = onAuthConnect,
                        isConnecting = state.isConnecting,
                        isServerAccessible = state.isServerAccessible,
                        onOpenSettings = openSettingsTab,
                        navigationRequest = pendingTrackersRequest,
                        onNavigationTargetConsumed = { pendingTrackersRequest = null },
                        onOpenTrackerOnMap = { trackerId, trackerName ->
                            openTrackerOnMap(trackerId, trackerName, MapReturnSource.TRACKERS)
                        },
                        onOpenGroupOnMap = { groupId ->
                            openGroupOnMap(groupId, MapReturnSource.TRACKERS)
                        },
                        onRequestTrackerParams = { args -> trackerParamsArgs = args },
                    )
                    }

                    TrackerTab.SHARED.name -> {
                    SharedScreen(
                        isAuthenticated = state.isAuthenticated,
                        serverUrl = state.serverUrl,
                        onAuthServerUrlChanged = onAuthServerUrlChanged,
                        onAuthConnect = onAuthConnect,
                        isConnecting = state.isConnecting,
                        onOpenSettings = openSettingsTab,
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

                    TrackerTab.SETTINGS.name -> {
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
                        onRefreshHiddenTrackerItems = onSettingsRefreshHiddenTrackerItems,
                        onUnhideTrackerItem = onSettingsUnhideTrackerItem,
                        onUnhideAllTrackerItems = onSettingsUnhideAllTrackerItems,
                        onOpenAllTrackersOnMap = {
                            if (!isHandlingTabBack &&
                                selectedTab.isNotBlank() &&
                                selectedTab != TrackerTab.MAP.name
                            ) {
                                tabBackStack = (tabBackStack + selectedTab).takeLast(16)
                            }
                            pendingMapReturnContext = null
                            mapViewModel.setMode(com.geovault.tracker.presentation.TrackerMapDisplayMode.ALL_QUEUE)
                            selectedTab = TrackerTab.MAP.name
                        },
                    )
                    }
                }
                }
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
        val updatePrompt = state.updatePrompt
        if (updatePrompt != null) {
            GeoVaultSnackbarHost(
                model = updatePrompt,
                onDismiss = onClearUpdatePrompt,
                onAction = { actionId ->
                    if (actionId == UpdateAvailablePromptComposer.ACTION_OPEN_RELEASE) {
                        state.updateReleaseUrl?.let { releaseLauncher.openReleasePage(it) }
                    }
                },
                stackBottomInset = if (globalInfoModel != null) 72.dp else 0.dp,
            )
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
    SETTINGS,
}
