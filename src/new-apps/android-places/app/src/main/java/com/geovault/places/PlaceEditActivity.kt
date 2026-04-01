package com.geovault.places

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.IntentCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Scaffold
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.geovault.common.CoordinateParser
import com.geovault.common.ui.components.GeoVaultInput
import com.geovault.common.ui.components.GeoVaultLoadingSpinner
import com.geovault.common.ui.components.GeoVaultPrimaryButton
import com.geovault.common.ui.components.GeoVaultSecondaryButton
import com.geovault.common.ui.components.GeoVaultTopTitleBar
import com.geovault.common.ui.components.GeoVaultTopTitleBarDefaults
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.common.ui.theme.GeoVaultTheme
import com.geovault.places.di.PlacesAppServices
import com.geovault.places.model.AddressSearchResult
import com.geovault.places.model.Feature
import com.geovault.places.model.Geometry
import com.geovault.places.model.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaceEditActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val editFeature = intent.serializableExtraCompat<Feature>("feature")
        val originalFeature = intent.serializableExtraCompat<Feature>("original_feature")
        val isOfflineEdit = intent.getBooleanExtra("is_offline_edit", false)
        val offlineEditIndex = intent.getIntExtra("offline_edit_index", -1)

        setContent {
            GeoVaultTheme {
                PlaceEditScreen(
                    initial = editFeature,
                    isOfflineEdit = isOfflineEdit,
                    onClose = { finish() },
                    onGeocodeSearch = { query ->
                        withContext(Dispatchers.IO) {
                            PlacesAppServices.from(application).placesRepository().geocodingSearch(query).getOrDefault(emptyList())
                        }
                    },
                    onSave = { updated ->
                        lifecycleScope.launch {
                            if (isOfflineEdit) {
                                val data = Intent().apply {
                                    putExtra("offline_feature", updated)
                                    putExtra("original_feature", originalFeature ?: editFeature)
                                    putExtra("offline_edit_index", offlineEditIndex)
                                }
                                setResult(RESULT_OK, data)
                                finish()
                                return@launch
                            }

                            val repo = PlacesAppServices.from(application).placesRepository()
                            val result = withContext(Dispatchers.IO) {
                                val dbId = editFeature?.properties?.database_id
                                if (dbId != null) repo.updatePlace(dbId, updated) else repo.createPlace(updated)
                            }
                            if (result.isSuccess) {
                                setResult(RESULT_OK, Intent().putExtra("updated_feature", result.getOrNull()))
                            } else {
                                val data = Intent().apply {
                                    putExtra("offline_feature", updated)
                                    putExtra("original_feature", editFeature)
                                    putExtra("offline_edit_index", offlineEditIndex)
                                }
                                setResult(RESULT_OK, data)
                            }
                            finish()
                        }
                    }
                )
            }
        }
    }

    private inline fun <reified T : java.io.Serializable> Intent.serializableExtraCompat(key: String): T? {
        return IntentCompat.getSerializableExtra(this, key, T::class.java)
    }
}

@Composable
private fun PlaceEditScreen(
    initial: Feature?,
    isOfflineEdit: Boolean,
    onClose: () -> Unit,
    onGeocodeSearch: suspend (String) -> List<AddressSearchResult>,
    onSave: (Feature) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.properties?.name.orEmpty()) }
    var description by remember { mutableStateOf(initial?.properties?.description.orEmpty()) }
    var coordinatesInput by remember {
        mutableStateOf(
            initial?.properties?.address
                ?: initial?.geometry?.coordinates?.takeIf { it.size >= 2 }?.let {
                    String.format("%.6f, %.6f", it[1], it[0])
                }.orEmpty()
        )
    }
    var selectedLat by remember { mutableStateOf(initial?.geometry?.coordinates?.getOrNull(1)) }
    var selectedLon by remember { mutableStateOf(initial?.geometry?.coordinates?.getOrNull(0)) }
    var selectedAddress by remember { mutableStateOf(initial?.properties?.address) }
    var searchResults by remember { mutableStateOf<List<AddressSearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var coordinatesError by remember { mutableStateOf<String?>(null) }
    var showDiscardDialog by remember { mutableStateOf(false) }

    val title = when {
        initial == null -> "New Place"
        isOfflineEdit -> "Edit Place (Offline)"
        else -> "Edit Place"
    }
    val initialName = initial?.properties?.name.orEmpty()
    val initialDescription = initial?.properties?.description.orEmpty()
    val initialCoordinates = initial?.properties?.address
        ?: initial?.geometry?.coordinates?.takeIf { it.size >= 2 }?.let {
            String.format("%.6f, %.6f", it[1], it[0])
        }.orEmpty()
    val isDirty = name.trim() != initialName.trim() ||
        description.trim() != initialDescription.trim() ||
        coordinatesInput.trim() != initialCoordinates.trim()

    LaunchedEffect(coordinatesInput) {
        val query = coordinatesInput.trim()
        if (query.isEmpty()) {
            searchResults = emptyList()
            coordinatesError = null
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(300)
        val parsed = CoordinateParser.parse(query)
        if (parsed != null) {
            val (lat, lon) = parsed
            searchResults = listOf(
                AddressSearchResult(
                    coordinates = listOf(lon, lat),
                    place_name = "Coordinates: ${String.format("%.6f", lat)}°, ${String.format("%.6f", lon)}°",
                    text = null
                )
            )
            coordinatesError = null
            return@LaunchedEffect
        }
        if (query.any { it.isLetter() && it.lowercaseChar() !in "nsewd" }) {
            isSearching = true
            searchResults = runCatching { onGeocodeSearch(query) }.getOrDefault(emptyList())
            isSearching = false
        } else {
            searchResults = emptyList()
        }
    }

    Scaffold(
        topBar = {
            GeoVaultTopTitleBar(
                title = title,
                rightActions = listOf(
                    GeoVaultTopTitleBarDefaults.closeAction(
                        onClick = {
                            if (isDirty) showDiscardDialog = true else onClose()
                        }
                    )
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .background(GeoVaultColorTokens.Surface)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Name *", color = GeoVaultColorTokens.TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            GeoVaultInput(
                value = name,
                onValueChange = { name = it },
                label = null,
                placeholder = "Place name",
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))
            Text("Description", color = GeoVaultColorTokens.TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            GeoVaultInput(
                value = description,
                onValueChange = { description = it },
                label = null,
                placeholder = "Optional description",
                singleLine = false,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Coordinates or Address *",
                    modifier = Modifier.weight(1f),
                    color = GeoVaultColorTokens.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                coordinatesError?.let {
                    Text(it, color = GeoVaultColorTokens.Error, fontSize = 12.sp)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GeoVaultInput(
                    value = coordinatesInput,
                    onValueChange = {
                        coordinatesInput = it
                        coordinatesError = null
                    },
                    label = null,
                    placeholder = "37.7749, -122.4194",
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        val parsed = CoordinateParser.parse(coordinatesInput.trim())
                        if (parsed != null) {
                            selectedLat = parsed.first
                            selectedLon = parsed.second
                            selectedAddress = null
                            coordinatesInput = String.format("%.6f, %.6f", parsed.first, parsed.second)
                            coordinatesError = null
                        } else {
                            coordinatesError = "Invalid coordinate format"
                        }
                    },
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Parse coordinates",
                        tint = GeoVaultColorTokens.TextSecondary
                    )
                }
            }

            if (isSearching) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    GeoVaultLoadingSpinner(spinnerSize = 20.dp)
                }
            }
            if (searchResults.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = GeoVaultColorTokens.Surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, GeoVaultColorTokens.BorderLight),
                    elevation = 0.dp
                ) {
                    Column {
                        searchResults.forEach { result ->
                            val titleText = result.text ?: result.place_name.orEmpty()
                            val subtitleText = result.place_name?.takeIf { it != titleText }
                            SearchResultRow(
                                title = titleText,
                                subtitle = subtitleText,
                                onClick = {
                                    val coords = result.coordinates
                                    if (coords != null && coords.size >= 2) {
                                        selectedLon = coords[0]
                                        selectedLat = coords[1]
                                        selectedAddress = result.place_name ?: result.text
                                        coordinatesInput = selectedAddress ?: String.format("%.6f, %.6f", coords[1], coords[0])
                                        searchResults = emptyList()
                                        coordinatesError = null
                                    }
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GeoVaultPrimaryButton(
                    text = "Save Place",
                    onClick = {
                        val normalizedName = name.trim()
                        if (normalizedName.isEmpty()) return@GeoVaultPrimaryButton
                        val lat = selectedLat
                        val lon = selectedLon
                        val parsed = if (lat != null && lon != null) null else CoordinateParser.parse(coordinatesInput.trim())
                        val finalLat = lat ?: parsed?.first
                        val finalLon = lon ?: parsed?.second
                        if (finalLat == null || finalLon == null) {
                            coordinatesError = "Invalid coordinates"
                            return@GeoVaultPrimaryButton
                        }
                        onSave(
                            Feature(
                                geometry = Geometry(coordinates = listOf(finalLon, finalLat)),
                                properties = Properties(
                                    database_id = initial?.properties?.database_id,
                                    name = normalizedName,
                                    description = description.trim(),
                                    created_at = initial?.properties?.created_at,
                                    address = selectedAddress
                                )
                            )
                        )
                    },
                    enabled = name.trim().isNotEmpty() && coordinatesInput.trim().isNotEmpty(),
                    modifier = Modifier.weight(1f)
                )
                GeoVaultSecondaryButton(
                    text = "Cancel",
                    onClick = {
                        if (isDirty) showDiscardDialog = true else onClose()
                    },
                    accentColor = GeoVaultColorTokens.TextSecondary,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Discard changes?") },
            text = { Text("You have unsaved changes. Are you sure you want to leave?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        onClose()
                    }
                ) {
                    Text("Discard", color = GeoVaultColorTokens.Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SearchResultRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)
            .height(48.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = title,
            color = GeoVaultColorTokens.TextSecondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                modifier = Modifier.padding(top = 2.dp),
                color = GeoVaultColorTokens.TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
