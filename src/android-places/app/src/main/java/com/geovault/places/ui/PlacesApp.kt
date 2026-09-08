package com.geovault.places.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Map
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.geovault.common.auth.GeoVaultAccountViewModel
import com.geovault.common.maps.core.GeoVaultMainMapPreloadHost
import com.geovault.common.maps.core.latLngOrNull
import com.geovault.common.maps.core.rememberGeoVaultMainMap
import com.geovault.common.maps.core.resolveGeoVaultMainMapPreloadCameraTarget
import com.geovault.common.ui.GeoVaultAppShell
import com.geovault.common.ui.GeoVaultAppSnackbarLayer
import com.geovault.common.ui.GeoVaultShellOverlayScaffold
import com.geovault.common.ui.auth.GeoVaultOAuthBrowserEffect
import com.geovault.common.ui.components.GeoVaultAccountOnlySettingsContent
import com.geovault.common.ui.components.GeoVaultBottomNavDestination
import com.geovault.common.ui.components.GeoVaultOverlayViewModelStoreOwner
import com.geovault.common.ui.components.GeoVaultShellSettingsOverlayHost
import com.geovault.common.ui.navigation.GeoVaultRegisterBackHandler
import com.geovault.common.ui.rememberGeoVaultAuthShellState
import com.geovault.common.util.ClipboardCopyHelper
import com.geovault.places.MainActivity
import com.geovault.places.PLACES_MAIN_MAP_KEY
import com.geovault.places.PlacesApplication
import com.geovault.places.R
import com.geovault.places.di.PlacesAppServices
import com.geovault.places.model.Place
import com.geovault.places.model.PlaceKey
import com.geovault.places.presentation.PlaceEditArgs
import com.geovault.places.presentation.PlaceEditViewModel
import com.geovault.places.presentation.PlacesMapViewModel
import com.geovault.places.presentation.PlacesShellViewModel

private enum class PlacesTab {
    LIST,
    MAP,
}

@Composable
fun PlacesApp(
    accountViewModel: GeoVaultAccountViewModel,
    clipboardCopyHelper: ClipboardCopyHelper,
) {
    val context = LocalContext.current
    val application = context.applicationContext as PlacesApplication
    val services = remember(application) { PlacesAppServices.from(application) }
    val shellViewModel: PlacesShellViewModel = viewModel(
        factory = PlacesShellViewModel.factory(services),
    )
    val mapViewModel: PlacesMapViewModel = viewModel(
        factory = PlacesMapViewModel.factory(services),
    )
    val state by shellViewModel.state.collectAsState()
    val accountState by accountViewModel.state.collectAsState()
    LaunchedEffect(accountState.isLoggedIn) {
        shellViewModel.onAccountStateChanged(accountState)
    }

    val mainMap = rememberGeoVaultMainMap(PLACES_MAIN_MAP_KEY)
    var selectedTab by rememberSaveable { mutableStateOf(PlacesTab.LIST.name) }
    var isSettingsOpen by rememberSaveable { mutableStateOf(false) }
    var isShareExportOpen by rememberSaveable { mutableStateOf(false) }
    var editKeyValue by rememberSaveable { mutableStateOf<String?>(null) }
    var isDescriptionOpen by rememberSaveable { mutableStateOf(false) }
    var descriptionTitle by rememberSaveable { mutableStateOf("") }
    var descriptionBody by rememberSaveable { mutableStateOf("") }
    var hasOpenedMapTab by rememberSaveable {
        mutableStateOf(selectedTab == PlacesTab.MAP.name)
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
    val preloadPoints = remember(state.sections) {
        (state.sections.waitingToSync + state.sections.saved).mapNotNull { place ->
            val location = place.content.location
            latLngOrNull(location.latitude, location.longitude)
        }
    }
    val preloadTarget = resolveGeoVaultMainMapPreloadCameraTarget(preloadPoints)
    GeoVaultOAuthBrowserEffect(
        oauthUrl = accountState.oauthUrl,
        onConsumed = accountViewModel::onOauthUrlConsumed,
    )
    GeoVaultRegisterBackHandler(
        canGoBack = { selectedTab != PlacesTab.LIST.name && editKeyValue == null && !isDescriptionOpen && !isSettingsOpen },
        onBack = {
            if (selectedTab == PlacesTab.LIST.name) return@GeoVaultRegisterBackHandler false
            selectedTab = PlacesTab.LIST.name
            true
        },
    )
    val openSettingsOverlay: () -> Unit = { isSettingsOpen = true }
    val auth = rememberGeoVaultAuthShellState(
        accountState = accountState,
        onServerUrlChanged = accountViewModel::onServerUrlChanged,
        onConnect = accountViewModel::connect,
        onOpenSettings = openSettingsOverlay,
    )
    val openEdit: (Place) -> Unit = { place -> editKeyValue = place.key.value }
    val openDescription: (Place) -> Unit = { place ->
        descriptionTitle = place.content.name.ifBlank { "Description" }
        descriptionBody = place.content.description
        isDescriptionOpen = true
    }
    val overlayOpen = isSettingsOpen || editKeyValue != null || isDescriptionOpen

    Box(modifier = Modifier.fillMaxSize()) {
        GeoVaultMainMapPreloadHost(
            mainMapKey = PLACES_MAIN_MAP_KEY,
            enabled = accountState.isLoggedIn && !hasOpenedMapTab,
            cameraTarget = preloadTarget,
            surfaceMapInHost = selectedTab != PlacesTab.MAP.name && !hasOpenedMapTab,
        )
        GeoVaultAppShell(
            destinations = bottomDestinations,
            selectedDestinationId = selectedTab,
            overlayNavBarChrome = overlayOpen,
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
                        GeoVaultAccountOnlySettingsContent(
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
                    placesStore = services.placesStore(),
                )
                editKeyValue?.let { keyValue ->
                    GeoVaultOverlayViewModelStoreOwner(entryId = "place-edit-$keyValue") {
                        val editViewModel: PlaceEditViewModel = viewModel(
                            factory = PlaceEditViewModel.factory(
                                services,
                                PlaceEditArgs(keyValue),
                            ),
                        )
                        PlaceEditScreen(
                            viewModel = editViewModel,
                            onClose = { editKeyValue = null },
                            onMessage = { message ->
                                shellViewModel.showSnackbar(message, "place_edit")
                            },
                        )
                    }
                }
                if (isDescriptionOpen) {
                    PlaceDescriptionOverlay(
                        args = PlaceDescriptionArgs(
                            title = descriptionTitle,
                            body = descriptionBody,
                        ),
                        onClose = { isDescriptionOpen = false },
                    )
                }
            },
            snackbarLayer = {
                GeoVaultAppSnackbarLayer(
                    snackbar = state.snackbar,
                    onDismissSnackbar = shellViewModel::clearSnackbar,
                    update = state.updateAvailable,
                    onDismissUpdate = shellViewModel::clearUpdateAvailable,
                )
            },
        ) { tabId, isActive ->
            when (tabId) {
                PlacesTab.LIST.name -> MainScreen(
                    state = state,
                    auth = auth,
                    onSearchChanged = shellViewModel::onSearchChanged,
                    onOpenShare = { isShareExportOpen = true },
                    onRefresh = { shellViewModel.refreshNow() },
                    onAddPlace = { editKeyValue = PlaceKey.local().value },
                    onEditPlace = openEdit,
                    onNavigatePlace = { place ->
                        shellViewModel.navigateToPlace(context, place)
                    },
                    onViewDescription = openDescription,
                    onOpenMapToPlace = { place ->
                        shellViewModel.openMapToPlace(place)
                        hasOpenedMapTab = true
                        selectedTab = PlacesTab.MAP.name
                    },
                    onCopyCoordinates = { text ->
                        clipboardCopyHelper.copyTextWithToast(
                            context = context,
                            text = text,
                            label = "Coordinates",
                            toastMessage = "Coordinates copied",
                        )
                    },
                    onCancelRefresh = shellViewModel::cancelRefresh,
                )
                PlacesTab.MAP.name -> PlacesMapScreen(
                    map = mainMap,
                    viewModel = mapViewModel,
                    launchArgs = state.mapLaunchArgs,
                    selectedKey = state.selectedKey,
                    auth = auth,
                    isTabVisible = isActive,
                    onOpenShare = { isShareExportOpen = true },
                    onSelectKey = shellViewModel::setSelectedKey,
                    onOpenEdit = openEdit,
                    onViewInList = { place ->
                        shellViewModel.setSelectedKey(place.key)
                        selectedTab = PlacesTab.LIST.name
                    },
                    onNavigate = { place ->
                        shellViewModel.navigateToPlace(context, place)
                    },
                    onViewDescription = openDescription,
                    onLaunchArgsConsumed = shellViewModel::onMapLaunchArgsConsumed,
                )
            }
        }
    }
}
