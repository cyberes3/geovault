package com.geovault.places

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.maps.core.rememberGeoVaultMainMapController
import com.geovault.common.ui.theme.GeoVaultTheme
import com.geovault.places.di.PlacesAppServices
import com.geovault.places.model.Feature
import com.geovault.places.model.OfflineFeature
import com.geovault.places.presentation.PlacesMapViewModel
import com.geovault.places.ui.PlacesMapLaunchArgs
import com.geovault.places.ui.PlacesMapScreen

class MapActivity : ComponentActivity() {
    companion object {
        const val EXTRA_SELECTED_ID_RESULT = "selected_id"
    }

    private val mapViewModel: PlacesMapViewModel by viewModels()
    private val editLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data
        val offlineFeature = data?.serializableExtraCompat<Feature>("offline_feature")
        val updatedFeature = data?.serializableExtraCompat<Feature>("updated_feature")
        val deletedFeature = data?.serializableExtraCompat<Feature>("deleted_feature")
        val revertedOffline = data?.serializableExtraCompat<OfflineFeature>("revert_offline_feature")
        when {
            deletedFeature != null -> {
                PlacesAppServices.from(application).cacheStore().removeCachedFeature(deletedFeature)
                PlacesAppServices.from(application).cacheStore().removeOfflineByFeature(deletedFeature)
            }

            revertedOffline != null -> {
                PlacesAppServices.from(application).cacheStore().removeOfflineByFeature(revertedOffline.feature)
                revertedOffline.original?.let { PlacesAppServices.from(application).cacheStore().updateCachedFeature(it) }
            }

            offlineFeature != null -> {
                val original = data.serializableExtraCompat<Feature>("original_feature")
                val offlineEditIndex = data.getIntExtra("offline_edit_index", -1)
                PlacesAppServices.from(application).cacheStore().addOrUpdateOffline(offlineFeature, original, offlineEditIndex)
            }

            updatedFeature != null -> {
                PlacesAppServices.from(application).cacheStore().updateCachedFeature(updatedFeature)
            }
        }
        mapViewModel.loadFromCache()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launchArgs = PlacesMapLaunchArgs(
            zoomToLat = intent.doubleExtraOrNull("zoom_to_lat"),
            zoomToLon = intent.doubleExtraOrNull("zoom_to_lon"),
            zoomToId = intent.intExtraOrNull("zoom_to_id"),
        )
        setContent {
            GeoVaultTheme {
                val controller = rememberGeoVaultMainMapController(PLACES_MAIN_MAP_KEY)
                PlacesMapScreen(
                    controller = controller,
                    viewModel = mapViewModel,
                    launchArgs = launchArgs,
                    onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                    onOpenEdit = { feature ->
                        val editIntent = Intent(this, PlaceEditActivity::class.java).apply {
                            putExtra("feature", feature)
                        }
                        editLauncher.launch(editIntent)
                    },
                    onViewInList = { feature ->
                        val dbId = feature.properties.database_id ?: return@PlacesMapScreen
                        setResult(RESULT_OK, Intent().putExtra(EXTRA_SELECTED_ID_RESULT, dbId))
                        finish()
                    },
                    onNavigate = { feature ->
                        val url = PlacesAppServices.from(application).navigationRepository().buildMapsSearchUrl(feature)
                        if (url != null) {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            PlacesAppServices.from(application).navigationRepository().trackNavigation(
                                feature = feature,
                                serverUrl = GeovaultAuthManager.getServerUrl(this),
                            )
                        }
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mapViewModel.loadFromCache()
    }

    private fun Intent.doubleExtraOrNull(key: String): Double? {
        return if (hasExtra(key)) getDoubleExtra(key, 0.0) else null
    }

    private fun Intent.intExtraOrNull(key: String): Int? {
        return if (hasExtra(key)) getIntExtra(key, -1) else null
    }

    private inline fun <reified T : java.io.Serializable> Intent.serializableExtraCompat(key: String): T? {
        return androidx.core.content.IntentCompat.getSerializableExtra(this, key, T::class.java)
    }
}
