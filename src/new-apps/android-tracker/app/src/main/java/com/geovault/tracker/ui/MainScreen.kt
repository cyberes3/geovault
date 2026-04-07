package com.geovault.tracker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.geovault.common.ui.components.GeoVaultBottomNavDestination
import com.geovault.common.ui.components.GeoVaultBottomNavScaffold
import com.geovault.common.ui.snackbar.GeoVaultSnackbarModel
import com.geovault.common.ui.snackbar.GeoVaultSnackbarHost
import com.geovault.common.update.CustomTabReleasePageLauncher
import com.geovault.common.update.UpdateAvailablePromptComposer
import com.geovault.tracker.presentation.HiddenMapItem
import com.geovault.tracker.presentation.MainScreenState
import com.geovault.tracker.presentation.SettingsState
import com.geovault.tracker.presentation.SharedSubTab
import com.geovault.tracker.presentation.SharedViewModel
import com.geovault.tracker.presentation.TrackerMapViewModel
import com.geovault.tracker.presentation.TrackersGroupsSubTab
import com.geovault.tracker.settings.TrackerTrackingProfile
import com.geovault.tracker.R

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
    onSettingsRefreshSelectableTrackers: () -> Unit,
    onSettingsSetSelectedTracker: (String) -> Unit,
    onSettingsClearSelectedTracker: () -> Unit,
    onSettingsRefreshHiddenMapItems: () -> Unit,
    onSettingsUnhideMapItem: (HiddenMapItem) -> Unit,
    onSettingsUnhideAllMapItems: () -> Unit,
) {
    val context = LocalContext.current
    val releaseLauncher = remember(context) { CustomTabReleasePageLauncher(context) }
    val mapViewModel: TrackerMapViewModel = viewModel()
    val sharedViewModel: SharedViewModel = viewModel()
    var selectedTab by rememberSaveable { mutableStateOf(TrackerTab.HOME.name) }
    var lastSelectedTab by rememberSaveable { mutableStateOf("") }
    var tabBackStack by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var isHandlingTabBack by remember { mutableStateOf(false) }
    var pendingTrackersRequest by remember { mutableStateOf<TrackersHostNavigationRequest?>(null) }
    var pendingSharedRequest by remember { mutableStateOf<SharedHostNavigationRequest?>(null) }
    var pendingMapReturnContext by remember { mutableStateOf<MapReturnContext?>(null) }
    var trackerParamsModel by remember { mutableStateOf<TrackerParamsUiModel?>(null) }
    LaunchedEffect(state.isAuthenticated) {
        if (state.isAuthenticated) {
            // Preload only main shared list data on app startup.
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
                    )
                )
                MapReturnSource.SHARED -> MapReturnContext(
                    tab = TrackerTab.SHARED,
                    sharedRequest = SharedHostNavigationRequest(
                        subTab = SharedSubTab.SHARED,
                        trackerId = trackerId,
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
                    )
                )
                MapReturnSource.SHARED -> MapReturnContext(
                    tab = TrackerTab.SHARED,
                    sharedRequest = SharedHostNavigationRequest(
                        subTab = SharedSubTab.SHARED,
                        groupId = groupId,
                    )
                )
            }
            selectedTab = TrackerTab.MAP.name
        }
    }
    BackHandler(enabled = selectedTab == TrackerTab.MAP.name && pendingMapReturnContext != null) {
        val context = pendingMapReturnContext ?: return@BackHandler
        pendingMapReturnContext = null
        pendingTrackersRequest = context.trackersRequest
        pendingSharedRequest = context.sharedRequest
        selectedTab = context.tab.name
    }
    BackHandler(
        enabled = selectedTab != TrackerTab.HOME.name &&
            tabBackStack.isNotEmpty() &&
            !(selectedTab == TrackerTab.MAP.name && pendingMapReturnContext != null)
    ) {
        val previous = tabBackStack.lastOrNull() ?: return@BackHandler
        isHandlingTabBack = true
        tabBackStack = tabBackStack.dropLast(1)
        pendingMapReturnContext = null
        selectedTab = previous
        isHandlingTabBack = false
    }
    val openSettingsTab = remember { { selectedTab = TrackerTab.SETTINGS.name } }
    val onMapHostNavigationRequested = remember {
        { request: MapHostNavigationRequest ->
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

    Box(modifier = Modifier.fillMaxSize()) {
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
                        onRequestTrackerParams = { model -> trackerParamsModel = model },
                    )
                }

                TrackerTab.MAP.name -> {
                    MapScreen(
                        mapViewModel = mapViewModel,
                        isAuthenticated = state.isAuthenticated,
                        serverUrl = state.serverUrl,
                        onAuthServerUrlChanged = onAuthServerUrlChanged,
                        onAuthConnect = onAuthConnect,
                        isConnecting = state.isConnecting,
                        onOpenSettings = openSettingsTab,
                        onHostNavigationRequested = onMapHostNavigationRequested,
                        onRequestTrackerParams = { model -> trackerParamsModel = model },
                    )
                }

                TrackerTab.TRACKERS.name -> {
                    TrackersScreen(
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
                        onRequestTrackerParams = { model -> trackerParamsModel = model },
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
                        onRequestTrackerParams = { model -> trackerParamsModel = model },
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
                        onRefreshSelectableTrackers = onSettingsRefreshSelectableTrackers,
                        onSetSelectedTracker = onSettingsSetSelectedTracker,
                        onClearSelectedTracker = onSettingsClearSelectedTracker,
                        onRefreshHiddenMapItems = onSettingsRefreshHiddenMapItems,
                        onUnhideMapItem = onSettingsUnhideMapItem,
                        onUnhideAllMapItems = onSettingsUnhideAllMapItems,
                        onOpenAllTrackersOnMap = {
                            pendingMapReturnContext = null
                            mapViewModel.setMode(com.geovault.tracker.presentation.TrackerMapDisplayMode.ALL_QUEUE)
                            selectedTab = TrackerTab.MAP.name
                        },
                    )
                }
            }
        }
        trackerParamsModel?.let { model ->
            TrackerParamsDialog(
                model = model,
                onDismiss = { trackerParamsModel = null },
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
