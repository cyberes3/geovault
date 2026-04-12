package com.geovault.places

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.IntentCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LocalContentColor
import androidx.compose.material.Scaffold
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.geovault.common.maps.core.GeoVaultStandardMapView
import com.geovault.common.maps.core.GeoVaultMapPhase
import com.geovault.common.maps.core.MapLibreManager
import com.geovault.common.maps.core.rememberGeoVaultStandardMap
import com.geovault.common.maps.location.rememberGeoVaultMapUserLocationPlugin
import com.geovault.common.maps.render.CommonMapIconIds
import com.geovault.common.maps.render.GeoJsonRenderConfig
import com.geovault.common.maps.render.GeoJsonRenderPlugin
import com.geovault.common.maps.render.MapRenderPoint
import com.geovault.common.maps.render.MapRenderState
import com.geovault.common.maps.ui.GeoVaultMapFabColumn
import com.geovault.common.maps.ui.GeoVaultMapFabIcon
import com.geovault.common.maps.ui.buildGeoVaultMapFabActions
import com.geovault.common.maps.ui.geoVaultLayerToggleFabAction
import com.geovault.common.maps.ui.rememberGeoVaultGpsRecenterController
import com.geovault.common.ui.components.GeoVaultConfirmationDialog
import com.geovault.common.ui.components.GeoVaultInput
import com.geovault.common.ui.components.GeoVaultLoadingSpinner
import com.geovault.common.ui.components.GeoVaultPrimaryButton
import com.geovault.common.ui.components.GeoVaultSecondaryButton
import com.geovault.common.ui.components.GeoVaultTopTitleBar
import com.geovault.common.ui.components.GeoVaultTopTitleBarDefaults
import com.geovault.common.ui.components.TopBarIconAction
import com.geovault.common.ui.modifier.geoVaultKeyboardAwareVerticalScroll
import com.geovault.common.ui.navigation.GeoVaultRegisterBackHandler
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.common.ui.theme.GeoVaultTheme
import com.geovault.places.di.PlacesAppServices
import com.geovault.places.model.AddressSearchResult
import com.geovault.places.model.Feature
import com.geovault.places.model.OfflineFeature
import com.geovault.places.presentation.PlaceEditScreenState
import com.geovault.places.presentation.PlacesOfflineBehaviorPolicy
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

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
                    onDeleteOrRevert = {
                        lifecycleScope.launch {
                            if (isOfflineEdit) {
                                val featureToRevert = editFeature ?: return@launch
                                val offline = OfflineFeature(
                                    feature = featureToRevert,
                                    original = originalFeature,
                                )
                                setResult(RESULT_OK, Intent().putExtra("revert_offline_feature", offline))
                                finish()
                                return@launch
                            }
                            val dbId = editFeature?.properties?.database_id ?: return@launch
                            val repo = PlacesAppServices.from(application).placesRepository()
                            val deleted = withContext(Dispatchers.IO) { repo.deletePlace(dbId).isSuccess }
                            if (deleted) {
                                setResult(RESULT_OK, Intent().putExtra("deleted_feature", editFeature))
                                finish()
                            } else {
                                Toast.makeText(
                                    this@PlaceEditActivity,
                                    PlacesOfflineBehaviorPolicy.DELETE_WHILE_OFFLINE_MESSAGE,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    },
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
    onDeleteOrRevert: () -> Unit,
    onGeocodeSearch: suspend (String) -> List<AddressSearchResult>,
    onSave: (Feature) -> Unit,
) {
    val state = remember(initial, isOfflineEdit) { PlaceEditScreenState(initial = initial, isOfflineEdit = isOfflineEdit) }
    val map = rememberGeoVaultStandardMap()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val dismissInputFocus = {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }
    val renderPlugin = remember(context) {
        GeoJsonRenderPlugin(
            sourceIdPrefix = "places-edit-map",
            config = GeoJsonRenderConfig(
                showPointCircles = false,
                showPointLabelsAndIcons = true,
                showPointTextLabels = false,
            ),
            context = context,
        )
    }
    val locationPlugin = rememberGeoVaultMapUserLocationPlugin(context = context)
    val gpsRecenterController = rememberGeoVaultGpsRecenterController(
        map = map,
        userLocation = locationPlugin,
        onLocationResolved = { latLng ->
            state.setFromDeviceLocation(latLng.latitude, latLng.longitude)
        },
        showUserLocationPuck = false,
    )
    val layerFabAction = remember(map) { geoVaultLayerToggleFabAction(map, order = 1) }
    var geocodeJob by remember { mutableStateOf<Job?>(null) }
    val formScrollState = rememberScrollState()

    GeoVaultRegisterBackHandler(
        canGoBack = { true },
        onBack = {
            if (state.hasUnsavedChanges) {
                state.showDiscardDialog = true
            } else {
                onClose()
            }
            true
        },
    )

    LaunchedEffect(map) {
        map.fetchSources()
    }

    DisposableEffect(map) {
        map.registerPlugin(renderPlugin)
        map.registerPlugin(locationPlugin)
        val listener = MapLibreMap.OnMapClickListener { clicked ->
            dismissInputFocus()
            state.setFromMapPoint(clicked.latitude, clicked.longitude)
            true
        }
        map.addOnMapClickListener(listener)
        onDispose {
            map.removeOnMapClickListener(listener)
            map.unregisterPlugin(renderPlugin)
            map.unregisterPlugin(locationPlugin)
        }
    }

    val phase by map.phase.collectAsState()
    LaunchedEffect(state.selectedLat, state.selectedLon, phase) {
        val lat = state.selectedLat ?: return@LaunchedEffect
        val lon = state.selectedLon ?: return@LaunchedEffect
        val points = if (state.showSelectedPointMarker) {
            listOf(
                MapRenderPoint(
                    id = "edit-selected-point",
                    latitude = lat,
                    longitude = lon,
                    iconImageId = CommonMapIconIds.MARKER_DEFAULT,
                    iconSize = 1f,
                )
            )
        } else {
            emptyList()
        }
        renderPlugin.setRenderState(
            MapRenderState(
                points = points,
            ),
        )
        if (phase != GeoVaultMapPhase.Ready) return@LaunchedEffect
        if (!state.shouldFocusCameraOnSelection()) return@LaunchedEffect
        map.animateCameraWithPadding(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(lat, lon),
                MapLibreManager.DEFAULT_POINT_ZOOM,
            )
        )
        state.markSelectionCameraFocusHandled()
    }

    LaunchedEffect(state.mapSearchQuery, state.showSearchPanel) {
        if (!state.showSearchPanel) {
            state.clearMapSearch()
            return@LaunchedEffect
        }
        val query = state.mapSearchQuery.trim()
        if (query.length < 2) {
            state.mapSearchResults = emptyList()
            state.isSearching = false
            return@LaunchedEffect
        }
        geocodeJob?.cancel()
        geocodeJob = scope.launch {
            delay(280)
            state.isSearching = true
            state.mapSearchResults = runCatching { onGeocodeSearch(query) }.getOrDefault(emptyList())
            state.isSearching = false
        }
    }

    Scaffold(
        backgroundColor = GeoVaultColorTokens.Background,
        topBar = {
            GeoVaultTopTitleBar(
                title = state.title,
                backgroundColor = GeoVaultColorTokens.PrimaryBlue,
                rightActions = if (initial != null) {
                    listOf(
                        TopBarIconAction(
                            icon = Icons.Filled.Delete,
                            contentDescription = state.deleteActionLabel(),
                            onClick = { state.showDeleteDialog = true },
                        ),
                        GeoVaultTopTitleBarDefaults.closeAction(
                            onClick = {
                                if (state.hasUnsavedChanges) state.showDiscardDialog = true else onClose()
                            },
                        ),
                    )
                } else {
                    listOf(
                        GeoVaultTopTitleBarDefaults.closeAction(
                            onClick = {
                                if (state.hasUnsavedChanges) state.showDiscardDialog = true else onClose()
                            },
                        ),
                    )
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(GeoVaultColorTokens.Background),
        ) {
            val mapMinHeight = maxHeight * 0.30f
            val formMinHeight = maxHeight * 0.56f
            val formMaxHeight = maxHeight * 0.64f
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .heightIn(min = mapMinHeight),
                ) {
                    GeoVaultStandardMapView(
                        map = map,
                        modifier = Modifier.fillMaxSize(),
                        includeDefaultFabColumnPadding = true,
                    )
                    GeoVaultMapFabColumn(
                        actions = buildGeoVaultMapFabActions {
                            action(
                                id = "search",
                                order = 0,
                                icon = GeoVaultMapFabIcon.Vector(Icons.Default.Search),
                                contentDescription = "Search location",
                                onTap = {
                                    dismissInputFocus()
                                    state.showSearchPanel = !state.showSearchPanel
                                },
                                emphasized = state.showSearchPanel,
                            )
                            action(
                                id = "layers",
                                order = 1,
                                icon = layerFabAction.icon,
                                contentDescription = "Switch map layer",
                                onTap = {
                                    dismissInputFocus()
                                    layerFabAction.onTap?.invoke()
                                },
                            )
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 16.dp, end = 16.dp),
                    )
                    if (state.showSearchPanel) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(12.dp)
                                .fillMaxWidth()
                                .height(240.dp),
                            shape = RoundedCornerShape(10.dp),
                            color = GeoVaultColorTokens.Surface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, GeoVaultColorTokens.BorderLight),
                            elevation = 0.dp,
                        ) {
                            Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                                GeoVaultInput(
                                    value = state.mapSearchQuery,
                                    onValueChange = { state.mapSearchQuery = it },
                                    label = null,
                                    placeholder = "Search address",
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                if (state.isSearching) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center,
                                    ) {
                                        GeoVaultLoadingSpinner(spinnerSize = 18.dp)
                                    }
                                } else {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .verticalScroll(rememberScrollState()),
                                    ) {
                                        state.mapSearchResults.forEach { result ->
                                            SearchResultRow(
                                                title = result.text ?: result.place_name.orEmpty(),
                                                subtitle = result.place_name?.takeIf { it != result.text },
                                                onClick = { state.setFromSearchResult(result) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(align = Alignment.Bottom)
                        .heightIn(min = formMinHeight)
                        .heightIn(max = formMaxHeight),
                    shape = androidx.compose.ui.graphics.RectangleShape,
                    color = GeoVaultColorTokens.Surface,
                    elevation = 0.dp,
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Divider(
                            modifier = Modifier.fillMaxWidth(),
                            color = GeoVaultColorTokens.BorderLight,
                            thickness = 1.dp,
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .geoVaultKeyboardAwareVerticalScroll(formScrollState)
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("Name *", color = GeoVaultColorTokens.TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            GeoVaultInput(
                                value = state.name,
                                onValueChange = { state.name = it },
                                label = null,
                                placeholder = "Place name",
                                modifier = Modifier.fillMaxWidth(),
                            )

                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Description", color = GeoVaultColorTokens.TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            GeoVaultInput(
                                value = state.description,
                                onValueChange = { state.description = it },
                                label = null,
                                placeholder = "Optional description",
                                singleLine = false,
                                modifier = Modifier.fillMaxWidth(),
                            )

                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Coordinates or Address *",
                                    modifier = Modifier.weight(1f),
                                    color = GeoVaultColorTokens.TextSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                state.coordinatesError?.let {
                                    Text(it, color = GeoVaultColorTokens.Error, fontSize = 12.sp)
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                GeoVaultInput(
                                    value = state.coordinatesInput,
                                    onValueChange = state::onCoordinatesEdited,
                                    label = null,
                                    placeholder = "37.7749, -122.4194",
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                GeoVaultSecondaryButton(
                                    text = "{ }",
                                    onClick = { state.parseCoordinatesFromInput() },
                                    tooltip = "Parse coordinates",
                                    fitToContent = true,
                                )
                            }

                            GeoVaultSecondaryButton(
                                text = "Use my location",
                                onClick = {
                                    dismissInputFocus()
                                    gpsRecenterController.onRecenter()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !gpsRecenterController.isLocking,
                                tooltip = "Use my location",
                                centeredContent = {
                                    val locationButtonTint = LocalContentColor.current
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        if (gpsRecenterController.isLocking) {
                                            GeoVaultLoadingSpinner(
                                                spinnerSize = 18.dp,
                                                color = locationButtonTint,
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Filled.MyLocation,
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp),
                                                tint = locationButtonTint,
                                            )
                                        }
                                        Text(
                                            text = "Use my location",
                                            color = locationButtonTint,
                                            fontSize = 14.sp,
                                        )
                                    }
                                },
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                GeoVaultPrimaryButton(
                                    text = "Save Place",
                                    onClick = {
                                        val built = state.buildFeatureOrNull() ?: return@GeoVaultPrimaryButton
                                        onSave(built)
                                    },
                                    enabled = state.name.trim().isNotEmpty() && state.coordinatesInput.trim().isNotEmpty(),
                                    modifier = Modifier.weight(1f),
                                )
                                GeoVaultSecondaryButton(
                                    text = "Cancel",
                                    onClick = {
                                        if (state.hasUnsavedChanges) state.showDiscardDialog = true else onClose()
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.showDiscardDialog) {
        GeoVaultConfirmationDialog(
            title = "Discard Changes?",
            message = "You have unsaved changes. Are you sure you want to leave?",
            onConfirm = {
                state.showDiscardDialog = false
                onClose()
            },
            onCancel = { state.showDiscardDialog = false },
            confirmText = "Discard",
            cancelText = "Cancel",
        )
    }

    if (state.showDeleteDialog && initial != null) {
        val actionLabel = state.deleteActionLabel()
        val message = if (state.isOfflineEdit) {
            if (initial.properties.database_id != null) {
                "Are you sure you want to revert your changes to '${initial.properties.name ?: "this place"}'?"
            } else {
                "Are you sure you want to discard '${initial.properties.name ?: "this place"}'?"
            }
        } else {
            "Are you sure you want to delete '${initial.properties.name ?: "this place"}'? This cannot be undone."
        }
        GeoVaultConfirmationDialog(
            title = "$actionLabel Place",
            message = message,
            onConfirm = {
                state.showDeleteDialog = false
                onDeleteOrRevert()
            },
            onCancel = { state.showDeleteDialog = false },
            confirmText = actionLabel,
            cancelText = "Cancel",
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
            .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            color = GeoVaultColorTokens.TextSecondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                modifier = Modifier.padding(top = 1.dp),
                color = GeoVaultColorTokens.TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
