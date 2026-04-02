package com.geovault.places

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
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
import androidx.core.content.IntentCompat
import com.geovault.common.ClipboardCopyHelper
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.maps.core.GeoVaultMainMapPreloadHost
import com.geovault.common.maps.core.rememberGeoVaultMainMap
import com.geovault.common.maps.core.resolveGeoVaultMainMapPreloadCameraTarget
import com.geovault.common.ui.components.GeoVaultBottomNavDestination
import com.geovault.common.ui.components.GeoVaultBottomNavScaffold
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
import org.maplibre.android.geometry.LatLng

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OAUTH_ERROR = "oauth_error"
        const val EXTRA_SHOW_EXPORT_SAVED_MESSAGE = "show_export_saved_message"
    }

    private val viewModel: MainScreenViewModel by viewModels()
    private val mapViewModel: PlacesMapViewModel by viewModels()
    private val clipboardCopyHelper: ClipboardCopyHelper by lazy { ClipboardCopyHelper(this) }

    private val editLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val data = it.data
        val offlineFeature = data?.serializableExtraCompat<Feature>("offline_feature")
        val updatedFeature = data?.serializableExtraCompat<Feature>("updated_feature")
        val deletedFeature = data?.serializableExtraCompat<Feature>("deleted_feature")
        val revertOffline = data?.serializableExtraCompat<OfflineFeature>("revert_offline_feature")
        when {
            deletedFeature != null -> {
                viewModel.onHostResumed()
            }
            revertOffline != null -> {
                viewModel.revertOfflineChanges(revertOffline)
            }
            offlineFeature != null -> {
                val original = data.serializableExtraCompat<Feature>("original_feature")
                val offlineEditIndex = data.getIntExtra("offline_edit_index", -1)
                viewModel.saveOffline(offlineFeature, original, offlineEditIndex)
            }
            updatedFeature != null -> {
                viewModel.applyUpdatedFeature(updatedFeature)
            }
            else -> viewModel.onHostResumed()
        }
        mapViewModel.loadFromCache()
    }

    private enum class PlacesTab {
        LIST,
        MAP,
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GeoVaultSystemBars.applyAppChrome(this)
        clipboardCopyHelper.prewarm()
        viewModel.initialize()
        setContent {
            GeoVaultTheme {
                val state by viewModel.state.collectAsState()
                val mainMap = rememberGeoVaultMainMap(PLACES_MAIN_MAP_KEY)
                var selectedTab by rememberSaveable { mutableStateOf(PlacesTab.LIST.name) }
                var hasOpenedMapTab by rememberSaveable {
                    mutableStateOf(selectedTab == PlacesTab.MAP.name)
                }
                var mapLaunchArgs by remember {
                    mutableStateOf(PlacesMapLaunchArgs())
                }
                val bottomDestinations = remember {
                    listOf(
                        GeoVaultBottomNavDestination(
                            id = PlacesTab.LIST.name,
                            label = "List",
                            icon = Icons.AutoMirrored.Filled.List,
                        ),
                        GeoVaultBottomNavDestination(
                            id = PlacesTab.MAP.name,
                            label = "Map",
                            icon = Icons.Default.Map,
                        ),
                    )
                }
                val preloadPoints = buildList {
                    state.saved.forEach { feature ->
                        val coords = feature.geometry.coordinates
                        if (coords.size >= 2) add(LatLng(coords[1], coords[0]))
                    }
                    state.offlineItems.forEach { offline ->
                        val coords = offline.feature.geometry.coordinates
                        if (coords.size >= 2) add(LatLng(coords[1], coords[0]))
                    }
                }
                val preloadTarget = resolveGeoVaultMainMapPreloadCameraTarget(preloadPoints)
                LaunchedEffect(state.oauthUrl) {
                    state.oauthUrl?.let { GeovaultAuthManager.launchOAuthInBrowser(this@MainActivity, it) }
                }
                LaunchedEffect(Unit) {
                    intent.getStringExtra(EXTRA_OAUTH_ERROR)?.let { error ->
                        viewModel.showExternalError(error)
                        intent?.removeExtra(EXTRA_OAUTH_ERROR)
                    }
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    GeoVaultMainMapPreloadHost(
                        mainMapKey = PLACES_MAIN_MAP_KEY,
                        enabled = state.isAuthenticated && !hasOpenedMapTab,
                        cameraTarget = preloadTarget,
                        surfaceMapInHost = selectedTab != PlacesTab.MAP.name && !hasOpenedMapTab,
                    )
                    GeoVaultBottomNavScaffold(
                        destinations = bottomDestinations,
                        selectedDestinationId = selectedTab,
                        onDestinationSelected = {
                            selectedTab = it.id
                            if (it.id == PlacesTab.MAP.name) {
                                hasOpenedMapTab = true
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    ) { destination ->
                        when (destination.id) {
                            PlacesTab.LIST.name -> {
                                MainScreen(
                                    state = state,
                                    onSearchChanged = viewModel::onSearchChanged,
                                    onAuthServerUrlChanged = viewModel::onAuthServerUrlChanged,
                                    onAuthConnect = viewModel::connectAuth,
                                    onOpenSettings = {
                                        startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                                    },
                                    onRefresh = viewModel::refreshNow,
                                    onAddPlace = {
                                        editLauncher.launch(Intent(this@MainActivity, PlaceEditActivity::class.java))
                                    },
                                    onEditSavedPlace = { feature ->
                                        val i = Intent(this@MainActivity, PlaceEditActivity::class.java)
                                        i.putExtra("feature", feature)
                                        editLauncher.launch(i)
                                    },
                                    onEditOfflinePlace = { offlineFeature, offlineIndex ->
                                        val i = Intent(this@MainActivity, PlaceEditActivity::class.java).apply {
                                            putExtra("feature", offlineFeature.feature)
                                            putExtra("original_feature", offlineFeature.original)
                                            putExtra("is_offline_edit", true)
                                            putExtra("offline_edit_index", offlineIndex)
                                        }
                                        editLauncher.launch(i)
                                    },
                                    onDeleteSavedPlace = viewModel::deleteSavedPlace,
                                    onRevertOrDiscardOffline = { offlineItem ->
                                        if (offlineItem.feature.properties.database_id != null) {
                                            viewModel.revertOfflineChanges(offlineItem)
                                        } else {
                                            viewModel.discardOfflineDraft(offlineItem)
                                        }
                                    },
                                    onNavigatePlace = { feature ->
                                        val url = PlacesAppServices.from(application).navigationRepository()
                                            .buildMapsSearchUrl(feature)
                                        if (url != null) {
                                            if (launchMapIntent(Uri.parse(url))) {
                                                PlacesAppServices.from(application).navigationRepository().trackNavigation(
                                                    feature,
                                                    GeovaultAuthManager.getServerUrl(this@MainActivity)
                                                )
                                            }
                                        }
                                    },
                                    onViewDescription = { feature ->
                                        val intent = Intent(this@MainActivity, DescriptionViewActivity::class.java).apply {
                                            putExtra(
                                                DescriptionViewActivity.EXTRA_TITLE,
                                                feature.properties.name ?: "Description"
                                            )
                                            putExtra(
                                                DescriptionViewActivity.EXTRA_DESCRIPTION,
                                                feature.properties.description.orEmpty()
                                            )
                                        }
                                        startActivity(intent)
                                    },
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
                                    onDismissSnackbar = viewModel::clearSnackbar,
                                )
                            }
                            PlacesTab.MAP.name -> {
                                PlacesMapScreen(
                                    map = mainMap,
                                    viewModel = mapViewModel,
                                    launchArgs = mapLaunchArgs,
                                    onOpenSettings = {
                                        startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                                    },
                                    onOpenEdit = { feature ->
                                        val editIntent = Intent(this@MainActivity, PlaceEditActivity::class.java).apply {
                                            putExtra("feature", feature)
                                        }
                                        editLauncher.launch(editIntent)
                                    },
                                    onViewInList = { feature ->
                                        viewModel.setSelectedPlaceId(feature.properties.database_id)
                                        selectedTab = PlacesTab.LIST.name
                                    },
                                    onNavigate = { feature ->
                                        val url = PlacesAppServices.from(application).navigationRepository()
                                            .buildMapsSearchUrl(feature)
                                        if (url != null) {
                                            if (launchMapIntent(Uri.parse(url))) {
                                                PlacesAppServices.from(application).navigationRepository().trackNavigation(
                                                    feature = feature,
                                                    serverUrl = GeovaultAuthManager.getServerUrl(this@MainActivity),
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        if (intent?.getBooleanExtra(EXTRA_SHOW_EXPORT_SAVED_MESSAGE, false) == true) {
            intent?.removeExtra(EXTRA_SHOW_EXPORT_SAVED_MESSAGE)
            Toast.makeText(this, "Offline data saved to Files -> Downloads", Toast.LENGTH_SHORT).show()
        }
        viewModel.onHostResumed()
        mapViewModel.loadFromCache()
    }

    override fun onStop() {
        super.onStop()
        viewModel.setSelectedPlaceId(null)
        viewModel.onOauthUrlConsumed()
    }

    private inline fun <reified T : java.io.Serializable> Intent.serializableExtraCompat(key: String): T? {
        return IntentCompat.getSerializableExtra(this, key, T::class.java)
    }

    private fun launchMapIntent(uri: Uri): Boolean {
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (_: ActivityNotFoundException) {
            viewModel.showExternalError(PlacesOfflineBehaviorPolicy.MAP_APP_UNAVAILABLE_MESSAGE)
            false
        }
    }
}