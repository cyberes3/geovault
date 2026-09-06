package com.geovault.places

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Map
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import com.geovault.common.util.ClipboardCopyHelper
import com.geovault.common.auth.GeoVaultAuthExtras
import com.geovault.common.intent.getSerializableExtraCompat
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.geovault.common.auth.GeoVaultAuthSession
import com.geovault.common.ui.auth.GeoVaultOAuthBrowserEffect
import com.geovault.common.maps.core.GeoVaultMainMapPreloadHost
import com.geovault.common.maps.core.isValidMapLibreGeographicLatLng
import com.geovault.common.maps.core.rememberGeoVaultMainMap
import com.geovault.common.maps.core.resolveGeoVaultMainMapPreloadCameraTarget
import com.geovault.common.auth.GeoVaultAccountViewModel
import com.geovault.common.ui.GeoVaultAppShell
import com.geovault.common.ui.GeoVaultAppSnackbarLayer
import com.geovault.common.ui.GeoVaultAuthShellState
import com.geovault.common.ui.GeoVaultShellOverlayScaffold
import com.geovault.common.ui.components.GeoVaultBottomNavDestination
import com.geovault.common.ui.components.GeoVaultShellSettingsOverlayHost
import com.geovault.common.ui.navigation.GeoVaultRegisterBackHandler
import com.geovault.common.ui.system.GeoVaultSystemBars
import com.geovault.common.ui.theme.GeoVaultTheme
import com.geovault.places.di.PlacesAppServices
import com.geovault.places.model.Feature
import com.geovault.places.model.OfflineFeature
import com.geovault.places.presentation.MainScreenViewModel
import com.geovault.places.presentation.PlacesOfflineBehaviorPolicy
import com.geovault.places.presentation.PlacesMapViewModel
import com.geovault.places.ui.MainScreen
import com.geovault.places.ui.PlacesMapLaunchArgs
import com.geovault.places.ui.PlacesMapScreen
import com.geovault.places.ui.PlacesShareExportHost
import com.geovault.places.ui.SettingsScreen
import org.maplibre.android.geometry.LatLng

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OAUTH_ERROR = GeoVaultAuthExtras.OAUTH_ERROR_EXTRA_KEY
        const val EXTRA_SHOW_EXPORT_SAVED_MESSAGE = "show_export_saved_message"
    }

    private val viewModel: MainScreenViewModel by viewModels()
    private val mapViewModel: PlacesMapViewModel by viewModels()
    private val accountViewModel: GeoVaultAccountViewModel by viewModels {
        GeoVaultAccountViewModel.factory(PlacesAppServices.from(application).initialAuthController())
    }
    private val clipboardCopyHelper: ClipboardCopyHelper by lazy { ClipboardCopyHelper(this) }

    private val editLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val data = it.data
        val offlineFeature = data?.getSerializableExtraCompat<Feature>(PlaceEditActivity.EXTRA_OFFLINE_FEATURE)
        val updatedFeature = data?.getSerializableExtraCompat<Feature>(PlaceEditActivity.EXTRA_UPDATED_FEATURE)
        val deletedFeature = data?.getSerializableExtraCompat<Feature>(PlaceEditActivity.EXTRA_DELETED_FEATURE)
        val revertOffline = data?.getSerializableExtraCompat<OfflineFeature>(PlaceEditActivity.EXTRA_REVERT_OFFLINE)
        when {
            deletedFeature != null -> {
                viewModel.applyDeletedFeature(deletedFeature)
            }
            revertOffline != null -> {
                viewModel.revertOfflineChanges(revertOffline)
            }
            offlineFeature != null -> {
                val original = data.getSerializableExtraCompat<Feature>(PlaceEditActivity.EXTRA_ORIGINAL_FEATURE)
                val clientLocalId = data.getStringExtra(PlaceEditActivity.EXTRA_CLIENT_LOCAL_ID)
                    ?: OfflineFeature.newId()
                val snackbar = data.getStringExtra(PlaceEditActivity.EXTRA_OFFLINE_SNACKBAR)
                    ?: PlacesOfflineBehaviorPolicy.SAVED_OFFLINE_MESSAGE
                viewModel.saveOffline(
                    feature = offlineFeature,
                    original = original,
                    clientLocalId = clientLocalId,
                    snackbarMessage = snackbar,
                )
            }
            updatedFeature != null -> {
                viewModel.applyUpdatedFeature(updatedFeature)
            }
            else -> viewModel.onHostResumed()
        }
    }

    private enum class PlacesTab {
        LIST,
        MAP,
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.geovault.common.ui.splash.GeoVaultSplashScreen.install(
            this,
            (application as PlacesApplication).bootstrap.isReady,
        )
        super.onCreate(savedInstanceState)
        GeoVaultSystemBars.applyAppChrome(this)
        clipboardCopyHelper.prewarm()
        accountViewModel.initialize()
        viewModel.initialize()
        setContent {
            GeoVaultTheme {
                val state by viewModel.state.collectAsState()
                val accountState by accountViewModel.state.collectAsState()
                val accountMainState = state.copy(
                    isAuthenticated = accountState.isLoggedIn,
                    serverUrl = accountState.serverUrl,
                    isConnecting = accountState.isConnecting,
                    oauthUrl = null,
                )
                LaunchedEffect(accountState.isLoggedIn, accountState.serverUrl, accountState.isConnecting) {
                    viewModel.onAccountStateChanged(accountState)
                }
                val mainMap = rememberGeoVaultMainMap(PLACES_MAIN_MAP_KEY)
                var selectedTab by rememberSaveable { mutableStateOf(PlacesTab.LIST.name) }
                var isSettingsOpen by rememberSaveable { mutableStateOf(false) }
                var isShareExportOpen by rememberSaveable { mutableStateOf(false) }
                var hasOpenedMapTab by rememberSaveable {
                    mutableStateOf(selectedTab == PlacesTab.MAP.name)
                }
                var mapLaunchArgs by remember {
                    mutableStateOf(PlacesMapLaunchArgs())
                }
                val listNavTooltip = stringResource(R.string.tooltip_nav_list)
                val mapNavTooltip = stringResource(R.string.tooltip_nav_map)
                val bottomDestinations = remember(listNavTooltip, mapNavTooltip) {
                    listOf(
                        GeoVaultBottomNavDestination(
                            id = PlacesTab.LIST.name,
                            label = "List",
                            icon = Icons.AutoMirrored.Filled.List,
                            tooltip = listNavTooltip,
                        ),
                        GeoVaultBottomNavDestination(
                            id = PlacesTab.MAP.name,
                            label = "Map",
                            icon = Icons.Default.Map,
                            tooltip = mapNavTooltip,
                        ),
                    )
                }
                val preloadPoints = buildList {
                    accountMainState.saved.forEach { feature ->
                        val coords = feature.geometry.coordinates
                        if (coords.size >= 2) {
                            val lat = coords[1]
                            val lon = coords[0]
                            if (isValidMapLibreGeographicLatLng(lat, lon)) {
                                add(LatLng(lat, lon))
                            }
                        }
                    }
                    accountMainState.offlineItems.forEach { offline ->
                        val coords = offline.feature.geometry.coordinates
                        if (coords.size >= 2) {
                            val lat = coords[1]
                            val lon = coords[0]
                            if (isValidMapLibreGeographicLatLng(lat, lon)) {
                                add(LatLng(lat, lon))
                            }
                        }
                    }
                }
                val preloadTarget = resolveGeoVaultMainMapPreloadCameraTarget(preloadPoints)
                GeoVaultOAuthBrowserEffect(
                    oauthUrl = accountState.oauthUrl,
                    onConsumed = accountViewModel::onOauthUrlConsumed,
                )
                LaunchedEffect(Unit) {
                    intent.getStringExtra(EXTRA_OAUTH_ERROR)?.let { error ->
                        accountViewModel.showExternalError(error)
                        intent?.removeExtra(EXTRA_OAUTH_ERROR)
                    }
                }
                // Root back: Map → List. Settings / share dialogs register their own handlers
                // (or use Dialog onDismissRequest). On List, defer to the system (finish).
                GeoVaultRegisterBackHandler(
                    canGoBack = { selectedTab != PlacesTab.LIST.name },
                    onBack = {
                        if (selectedTab == PlacesTab.LIST.name) return@GeoVaultRegisterBackHandler false
                        selectedTab = PlacesTab.LIST.name
                        true
                    },
                )
                val openSettingsOverlay: () -> Unit = { isSettingsOpen = true }
                val auth = remember(
                    accountMainState.isAuthenticated,
                    accountMainState.serverUrl,
                    accountMainState.isConnecting,
                ) {
                    GeoVaultAuthShellState(
                        isAuthenticated = accountMainState.isAuthenticated,
                        serverUrl = accountMainState.serverUrl,
                        onServerUrlChanged = accountViewModel::onServerUrlChanged,
                        onConnect = accountViewModel::connect,
                        onOpenSettings = openSettingsOverlay,
                        isConnecting = accountMainState.isConnecting,
                    )
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    GeoVaultMainMapPreloadHost(
                        mainMapKey = PLACES_MAIN_MAP_KEY,
                        enabled = accountMainState.isAuthenticated && !hasOpenedMapTab,
                        cameraTarget = preloadTarget,
                        surfaceMapInHost = selectedTab != PlacesTab.MAP.name && !hasOpenedMapTab,
                    )
                    GeoVaultAppShell(
                        destinations = bottomDestinations,
                        selectedDestinationId = selectedTab,
                        overlayNavBarChrome = isSettingsOpen,
                        onDestinationSelected = {
                            selectedTab = it.id
                            if (it.id == PlacesTab.MAP.name) {
                                hasOpenedMapTab = true
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        overlay = {
                            GeoVaultShellSettingsOverlayHost(
                                visible = isSettingsOpen,
                                onDismissRequest = { isSettingsOpen = false },
                            ) {
                                GeoVaultShellOverlayScaffold(
                                    title = stringResource(R.string.nav_settings),
                                    onClose = { isSettingsOpen = false },
                                ) { padding ->
                                    SettingsScreen(
                                        accountState = accountState,
                                        onServerUrlChanged = accountViewModel::onServerUrlChanged,
                                        onConnect = accountViewModel::connect,
                                        onDisconnect = { accountViewModel.disconnect(MainActivity::class.java) },
                                        contentPadding = padding,
                                    )
                                }
                            }
                            PlacesShareExportHost(
                                visible = isShareExportOpen,
                                onDismissRequest = { isShareExportOpen = false },
                                placesStore = PlacesAppServices.from(application).placesStore(),
                            )
                        },
                        snackbarLayer = {
                            GeoVaultAppSnackbarLayer(
                                snackbar = accountMainState.snackbar,
                                onDismissSnackbar = viewModel::clearSnackbar,
                                update = accountMainState.updateAvailable,
                                onDismissUpdate = viewModel::clearUpdateAvailable,
                            )
                        },
                    ) { tabId, isActive ->
                        when (tabId) {
                            PlacesTab.LIST.name -> MainScreen(
                                state = accountMainState,
                                auth = auth,
                                onSearchChanged = viewModel::onSearchChanged,
                                onOpenShare = { isShareExportOpen = true },
                                onRefresh = viewModel::refreshNow,
                                onAddPlace = {
                                    editLauncher.launch(
                                        Intent(this@MainActivity, PlaceEditActivity::class.java).apply {
                                            putExtra(PlaceEditActivity.EXTRA_CLIENT_LOCAL_ID, OfflineFeature.newId())
                                        },
                                    )
                                },
                                onEditSavedPlace = { feature ->
                                    editLauncher.launch(buildEditIntent(feature))
                                },
                                onEditOfflinePlace = { offlineFeature ->
                                    val i = Intent(this@MainActivity, PlaceEditActivity::class.java).apply {
                                        putExtra(PlaceEditActivity.EXTRA_FEATURE, offlineFeature.feature)
                                        putExtra(PlaceEditActivity.EXTRA_ORIGINAL_FEATURE, offlineFeature.original)
                                        putExtra(PlaceEditActivity.EXTRA_IS_OFFLINE_EDIT, true)
                                        putExtra(PlaceEditActivity.EXTRA_CLIENT_LOCAL_ID, offlineFeature.clientLocalId)
                                    }
                                    editLauncher.launch(i)
                                },
                                onNavigatePlace = { feature ->
                                    val navigationRepository = PlacesAppServices.from(application).navigationRepository()
                                    if (navigationRepository.openInGoogleMaps(
                                            context = this@MainActivity,
                                            feature = feature,
                                            onUnavailable = {
                                                viewModel.showExternalError(
                                                    PlacesOfflineBehaviorPolicy.MAP_APP_UNAVAILABLE_MESSAGE,
                                                )
                                            },
                                        )
                                    ) {
                                        navigationRepository.trackNavigation(
                                            feature,
                                            GeoVaultAuthSession.get().getServerUrl(),
                                        )
                                    }
                                },
                                onViewDescription = ::openDescriptionView,
                                onOpenMapToPlace = { feature ->
                                    val coords = feature.geometry.coordinates
                                    mapLaunchArgs = PlacesMapLaunchArgs(
                                        zoomToLat = coords.getOrNull(1),
                                        zoomToLon = coords.getOrNull(0),
                                        zoomToId = feature.properties.database_id,
                                        requestToken = System.currentTimeMillis(),
                                    )
                                    hasOpenedMapTab = true
                                    selectedTab = PlacesTab.MAP.name
                                },
                                onCopyCoordinates = { text ->
                                    if (clipboardCopyHelper.copyText(text = text, label = "Coordinates")) {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Coordinates copied",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                onCancelRefresh = {
                                    viewModel.cancelRefresh()
                                },
                            )
                            PlacesTab.MAP.name -> PlacesMapScreen(
                                map = mainMap,
                                viewModel = mapViewModel,
                                launchArgs = mapLaunchArgs,
                                auth = auth,
                                isTabVisible = isActive,
                                onOpenShare = { isShareExportOpen = true },
                                onOpenEdit = { feature ->
                                    editLauncher.launch(buildEditIntent(feature))
                                },
                                onViewInList = { feature ->
                                    viewModel.setSelectedPlaceId(feature.properties.database_id)
                                    selectedTab = PlacesTab.LIST.name
                                },
                                onNavigate = { feature ->
                                    val navigationRepository = PlacesAppServices.from(application).navigationRepository()
                                    if (navigationRepository.openInGoogleMaps(
                                            context = this@MainActivity,
                                            feature = feature,
                                            onUnavailable = {
                                                viewModel.showExternalError(
                                                    PlacesOfflineBehaviorPolicy.MAP_APP_UNAVAILABLE_MESSAGE,
                                                )
                                            },
                                        )
                                    ) {
                                        navigationRepository.trackNavigation(
                                            feature = feature,
                                            serverUrl = GeoVaultAuthSession.get().getServerUrl(),
                                        )
                                    }
                                },
                                onViewDescription = ::openDescriptionView,
                                onLaunchArgsConsumed = {
                                    mapLaunchArgs = PlacesMapLaunchArgs(requestToken = mapLaunchArgs.requestToken)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_OAUTH_ERROR)?.let { error ->
            accountViewModel.showExternalError(error)
            intent.removeExtra(EXTRA_OAUTH_ERROR)
        }
    }

    override fun onResume() {
        super.onResume()
        val showExportToast = intent?.getBooleanExtra(EXTRA_SHOW_EXPORT_SAVED_MESSAGE, false) == true ||
            PlacesApplication.consumePendingExportSavedToast()
        if (showExportToast) {
            intent?.removeExtra(EXTRA_SHOW_EXPORT_SAVED_MESSAGE)
            Toast.makeText(this, "Offline data saved to Files -> Downloads", Toast.LENGTH_SHORT).show()
        }
        accountViewModel.onHostResumed()
        viewModel.onHostResumed()
    }

    override fun onStop() {
        super.onStop()
        accountViewModel.onOauthUrlConsumed()
    }

    private fun openDescriptionView(feature: Feature) {
        val intent = Intent(this, DescriptionViewActivity::class.java).apply {
            putExtra(DescriptionViewActivity.EXTRA_TITLE, feature.properties.name ?: "Description")
            putExtra(DescriptionViewActivity.EXTRA_DESCRIPTION, feature.properties.description.orEmpty())
        }
        startActivity(intent)
    }

    /**
     * Builds the edit intent for a feature that only has its merged display representation
     * (e.g. tapped from the map). If it matches a pending offline edit, routes through the same
     * offline-edit extras the list screen uses so the edit updates the existing queue entry
     * instead of attempting an online PUT (or creating a duplicate offline entry).
     */
    private fun buildEditIntent(feature: Feature): Intent {
        val offlineMatch = PlacesAppServices.from(application).placesStore()
            .findOfflineForFeature(feature)
        return Intent(this, PlaceEditActivity::class.java).apply {
            if (offlineMatch != null) {
                putExtra(PlaceEditActivity.EXTRA_FEATURE, offlineMatch.feature)
                putExtra(PlaceEditActivity.EXTRA_ORIGINAL_FEATURE, offlineMatch.original)
                putExtra(PlaceEditActivity.EXTRA_IS_OFFLINE_EDIT, true)
                putExtra(PlaceEditActivity.EXTRA_CLIENT_LOCAL_ID, offlineMatch.clientLocalId)
            } else {
                putExtra(PlaceEditActivity.EXTRA_FEATURE, feature)
                putExtra(PlaceEditActivity.EXTRA_CLIENT_LOCAL_ID, OfflineFeature.newId())
            }
        }
    }
}