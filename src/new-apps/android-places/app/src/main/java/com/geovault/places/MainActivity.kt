package com.geovault.places

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.content.IntentCompat
import com.geovault.common.ClipboardCopyHelper
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.maps.core.GeoVaultMainMapPreloadHost
import com.geovault.common.maps.core.resolveGeoVaultMainMapPreloadCameraTarget
import com.geovault.common.ui.system.GeoVaultSystemBars
import com.geovault.common.ui.theme.GeoVaultTheme
import com.geovault.places.di.PlacesAppServices
import com.geovault.places.model.Feature
import com.geovault.places.model.OfflineFeature
import com.geovault.places.presentation.MainScreenViewModel
import com.geovault.places.ui.MainScreen
import org.maplibre.android.geometry.LatLng

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OAUTH_ERROR = "oauth_error"
        const val EXTRA_SELECTED_ID_FROM_MAP = "selected_id_from_map"
    }

    private val viewModel: MainScreenViewModel by viewModels()
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GeoVaultSystemBars.applyAppChrome(this)
        clipboardCopyHelper.prewarm()
        viewModel.initialize()
        setContent {
            GeoVaultTheme {
                val state by viewModel.state.collectAsState()
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
                    intent.getStringExtra(EXTRA_OAUTH_ERROR)?.let {
                        viewModel.clearSnackbar()
                    }
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    GeoVaultMainMapPreloadHost(
                        mainMapKey = PLACES_MAIN_MAP_KEY,
                        enabled = state.isAuthenticated,
                        cameraTarget = preloadTarget,
                    )
                    MainScreen(
                        state = state,
                        onSearchChanged = viewModel::onSearchChanged,
                        onAuthServerUrlChanged = viewModel::onAuthServerUrlChanged,
                        onAuthConnect = viewModel::connectAuth,
                        onOpenSettings = { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) },
                        onRefresh = viewModel::refreshNow,
                        onAddPlace = { editLauncher.launch(Intent(this@MainActivity, PlaceEditActivity::class.java)) },
                        onOpenMap = { startActivity(Intent(this@MainActivity, MapActivity::class.java)) },
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
                        onNavigatePlace = { feature ->
                            val url = PlacesAppServices.from(application).navigationRepository().buildMapsSearchUrl(feature)
                            if (url != null) {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                PlacesAppServices.from(application).navigationRepository().trackNavigation(
                                    feature,
                                    GeovaultAuthManager.getServerUrl(this@MainActivity)
                                )
                            }
                        },
                        onViewDescription = { feature ->
                            val intent = Intent(this@MainActivity, DescriptionViewActivity::class.java).apply {
                                putExtra(DescriptionViewActivity.EXTRA_TITLE, feature.properties.name ?: "Description")
                                putExtra(DescriptionViewActivity.EXTRA_DESCRIPTION, feature.properties.description.orEmpty())
                            }
                            startActivity(intent)
                        },
                        onOpenMapToPlace = { feature ->
                            val intent = Intent(this@MainActivity, MapActivity::class.java)
                            val coords = feature.geometry.coordinates
                            if (coords.size >= 2) {
                                intent.putExtra("zoom_to_lat", coords[1])
                                intent.putExtra("zoom_to_lon", coords[0])
                                intent.putExtra("zoom_to_id", feature.properties.database_id ?: -1)
                            }
                            startActivity(intent)
                        },
                        onCopyCoordinates = { text ->
                            if (clipboardCopyHelper.copyText(text = text, label = "Coordinates")) {
                                Toast.makeText(this@MainActivity, "Coordinates copied", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onCancelRefresh = {
                            viewModel.cancelRefresh()
                            Toast.makeText(this@MainActivity, "Cancelled - using cached data", Toast.LENGTH_SHORT).show()
                        },
                        onDismissSnackbar = viewModel::clearSnackbar,
                    )
                }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        val selectedIdFromMap = intent?.getIntExtra(EXTRA_SELECTED_ID_FROM_MAP, -1) ?: -1
        if (selectedIdFromMap != -1) {
            intent?.removeExtra(EXTRA_SELECTED_ID_FROM_MAP)
            viewModel.setSelectedPlaceId(selectedIdFromMap)
        }
        viewModel.onHostResumed()
    }

    override fun onStop() {
        super.onStop()
        viewModel.setSelectedPlaceId(null)
        viewModel.onOauthUrlConsumed()
    }

    private inline fun <reified T : java.io.Serializable> Intent.serializableExtraCompat(key: String): T? {
        return IntentCompat.getSerializableExtra(this, key, T::class.java)
    }
}