package com.geovault.places

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.geovault.common.ui.components.GeoVaultInput
import com.geovault.common.ui.components.GeoVaultPrimaryButton
import com.geovault.common.ui.components.GeoVaultTopTitleBar
import com.geovault.common.ui.components.GeoVaultTopTitleBarDefaults
import com.geovault.common.ui.theme.GeoVaultTheme
import com.geovault.places.di.PlacesAppServices
import com.geovault.places.model.Feature
import com.geovault.places.model.Geometry
import com.geovault.places.model.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaceEditActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val existing = intent.getSerializableExtra("feature", Feature::class.java)
        setContent {
            GeoVaultTheme {
                PlaceEditScreen(
                    initial = existing,
                    onClose = { finish() },
                    onSave = { updated ->
                        lifecycleScope.launch {
                            val repo = PlacesAppServices.from(application).placesRepository()
                            val result = withContext(Dispatchers.IO) {
                                val dbId = existing?.properties?.database_id
                                if (dbId != null) repo.updatePlace(dbId, updated) else repo.createPlace(updated)
                            }
                            if (result.isSuccess) {
                                setResult(RESULT_OK, Intent().putExtra("updated_feature", result.getOrNull()))
                                finish()
                            } else {
                                PlacesAppServices.from(application).cacheStore()
                                    .addOrUpdateOffline(updated, existing, -1)
                                setResult(RESULT_OK, Intent().putExtra("offline_feature", updated))
                                finish()
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun PlaceEditScreen(
    initial: Feature?,
    onClose: () -> Unit,
    onSave: (Feature) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.properties?.name.orEmpty()) }
    var description by remember { mutableStateOf(initial?.properties?.description.orEmpty()) }
    var lat by remember { mutableStateOf(initial?.geometry?.coordinates?.getOrNull(1)?.toString().orEmpty()) }
    var lon by remember { mutableStateOf(initial?.geometry?.coordinates?.getOrNull(0)?.toString().orEmpty()) }

    Scaffold(
        topBar = {
            GeoVaultTopTitleBar(
                title = if (initial == null) "Add Place" else "Edit Place",
                rightActions = listOf(GeoVaultTopTitleBarDefaults.closeAction(onClick = onClose))
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GeoVaultInput(value = name, onValueChange = { name = it }, label = "Name", modifier = Modifier.fillMaxWidth())
            GeoVaultInput(
                value = description,
                onValueChange = { description = it },
                label = "Description",
                modifier = Modifier.fillMaxWidth(),
            )
            GeoVaultInput(value = lat, onValueChange = { lat = it }, label = "Latitude", modifier = Modifier.fillMaxWidth())
            GeoVaultInput(value = lon, onValueChange = { lon = it }, label = "Longitude", modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(12.dp))
            GeoVaultPrimaryButton(
                text = "Save",
                enabled = name.isNotBlank() && lat.toDoubleOrNull() != null && lon.toDoubleOrNull() != null,
                onClick = {
                    val latitude = lat.toDoubleOrNull() ?: return@GeoVaultPrimaryButton
                    val longitude = lon.toDoubleOrNull() ?: return@GeoVaultPrimaryButton
                    onSave(
                        Feature(
                            geometry = Geometry(coordinates = listOf(longitude, latitude)),
                            properties = Properties(
                                database_id = initial?.properties?.database_id,
                                name = name,
                                description = description,
                                created_at = initial?.properties?.created_at,
                                address = initial?.properties?.address,
                            )
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Tip: Use Map screen for visual lookup and coordinate copy.")
        }
    }
}
