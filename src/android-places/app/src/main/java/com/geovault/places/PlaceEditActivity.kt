package com.geovault.places

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Icon
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.geovault.places.R
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.geovault.common.intent.getSerializableExtraCompat
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.common.maps.core.GeoVaultStandardMapView
import com.geovault.common.maps.core.GeoVaultMapPhase
import com.geovault.common.maps.core.MapLibreManager
import com.geovault.common.maps.core.isValidMapLibreGeographicLatLng
import com.geovault.common.maps.core.rememberGeoVaultStandardMap
import com.geovault.common.maps.location.rememberGeoVaultMapUserLocationPlugin
import com.geovault.common.maps.render.CommonMapIconIds
import com.geovault.common.maps.render.GeoJsonRenderConfig
import com.geovault.common.maps.render.GeoJsonRenderPlugin
import com.geovault.common.maps.render.MapRenderPoint
import com.geovault.common.maps.render.MapRenderState
import com.geovault.common.maps.ui.GeoVaultMapFabColumn
import com.geovault.common.maps.ui.GeoVaultMapFabIcon
import com.geovault.common.maps.geocoding.GeocodingRepository
import com.geovault.common.maps.ui.buildGeoVaultMapFabActions
import com.geovault.common.maps.ui.geoVaultLayerToggleFabAction
import com.geovault.common.maps.ui.geocoding.GeoVaultMapGeocodeSearchDialog
import com.geovault.common.maps.ui.oneshot.rememberGeoVaultGpsOneShotMyLocationController
import com.geovault.common.sync.GeoVaultHttpFailureClassifier
import com.geovault.common.sync.GeoVaultHttpFailureKind
import com.geovault.common.sync.GeoVaultQueuedSyncFailurePolicy
import com.geovault.common.sync.GeoVaultQueuedSyncItemDisposition
import com.geovault.common.ui.GeoVaultAppSnackbarLayer
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
import com.geovault.common.ui.snackbar.GeoVaultSnackbarModel
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.common.ui.theme.GeoVaultTheme
import com.geovault.common.ui.theme.geoVaultContentSecondaryColor
import com.geovault.common.ui.theme.geoVaultHairlineDividerColor
import com.geovault.places.di.PlacesAppServices
import com.geovault.places.model.Feature
import com.geovault.places.model.OfflineFeature
import com.geovault.places.presentation.PlaceEditScreenState
import com.geovault.places.presentation.PlacesOfflineBehaviorPolicy
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

class PlaceEditActivity : ComponentActivity() {
    companion object {
        private const val TAG = "PlacesEdit"
        const val EXTRA_CLIENT_LOCAL_ID = "client_local_id"
        const val EXTRA_IS_OFFLINE_EDIT = "is_offline_edit"
        const val EXTRA_FEATURE = "feature"
        const val EXTRA_ORIGINAL_FEATURE = "original_feature"
        const val EXTRA_OFFLINE_FEATURE = "offline_feature"
        const val EXTRA_UPDATED_FEATURE = "updated_feature"
        const val EXTRA_DELETED_FEATURE = "deleted_feature"
        const val EXTRA_REVERT_OFFLINE = "revert_offline_feature"
        const val EXTRA_OFFLINE_SNACKBAR = "offline_snackbar_message"
    }

    private val saveInFlight = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val editFeature = intent.getSerializableExtraCompat<Feature>(EXTRA_FEATURE)
        val originalFeature = intent.getSerializableExtraCompat<Feature>(EXTRA_ORIGINAL_FEATURE)
        val isOfflineEdit = intent.getBooleanExtra(EXTRA_IS_OFFLINE_EDIT, false)
        val clientLocalId = intent.getStringExtra(EXTRA_CLIENT_LOCAL_ID)
            ?: OfflineFeature.newId()

        setContent {
            GeoVaultTheme {
                var snackbarMessage by remember { mutableStateOf<String?>(null) }
                Box(modifier = Modifier.fillMaxSize()) {
                PlaceEditScreen(
                    initial = editFeature,
                    isOfflineEdit = isOfflineEdit,
                    onClose = { finish() },
                    onDeleteOrRevert = {
                        lifecycleScope.launch {
                            if (isOfflineEdit) {
                                val featureToRevert = editFeature ?: return@launch
                                val offline = OfflineFeature(
                                    clientLocalId = clientLocalId,
                                    feature = featureToRevert,
                                    original = originalFeature,
                                )
                                setResult(
                                    RESULT_OK,
                                    Intent().putExtra(EXTRA_REVERT_OFFLINE, offline),
                                )
                                finish()
                                return@launch
                            }
                            val dbId = editFeature?.properties?.database_id ?: return@launch
                            val repo = PlacesAppServices.from(application).placesRepository()
                            val result = withContext(Dispatchers.IO) { repo.deletePlace(dbId) }
                            if (result.isSuccess) {
                                setResult(
                                    RESULT_OK,
                                    Intent().putExtra(EXTRA_DELETED_FEATURE, editFeature),
                                )
                                finish()
                            } else {
                                val kind = GeoVaultHttpFailureClassifier.classifyThrowable(
                                    result.exceptionOrNull() ?: Exception("delete failed"),
                                )
                                val message = when (kind) {
                                    GeoVaultHttpFailureKind.Auth ->
                                        PlacesOfflineBehaviorPolicy.AUTH_REQUIRED_MESSAGE
                                    GeoVaultHttpFailureKind.RetryableNetwork ->
                                        PlacesOfflineBehaviorPolicy.DELETE_WHILE_OFFLINE_MESSAGE
                                    else -> PlacesOfflineBehaviorPolicy.DELETE_SERVER_ERROR_MESSAGE
                                }
                                snackbarMessage = message
                            }
                        }
                    },
                    onSave = { updated ->
                        if (!saveInFlight.compareAndSet(false, true)) {
                            GeoVaultCaptureLog.w(TAG, "save ignored: already in flight")
                            return@PlaceEditScreen
                        }
                        lifecycleScope.launch {
                            try {
                                if (isOfflineEdit) {
                                    GeoVaultCaptureLog.i(
                                        TAG,
                                        "save offline-edit clientLocalId=$clientLocalId " +
                                            "name=${updated.properties.name} " +
                                            "databaseId=${updated.properties.database_id}",
                                    )
                                    setResult(
                                        RESULT_OK,
                                        Intent().apply {
                                            putExtra(EXTRA_OFFLINE_FEATURE, updated)
                                            putExtra(
                                                EXTRA_ORIGINAL_FEATURE,
                                                originalFeature ?: editFeature,
                                            )
                                            putExtra(EXTRA_CLIENT_LOCAL_ID, clientLocalId)
                                            putExtra(
                                                EXTRA_OFFLINE_SNACKBAR,
                                                PlacesOfflineBehaviorPolicy.SAVED_OFFLINE_MESSAGE,
                                            )
                                        },
                                    )
                                    finish()
                                    return@launch
                                }

                                val repo = PlacesAppServices.from(application).placesRepository()
                                val dbId = editFeature?.properties?.database_id
                                GeoVaultCaptureLog.i(
                                    TAG,
                                    "save online-attempt name=${updated.properties.name} databaseId=$dbId " +
                                        "hasCreatedAt=${!updated.properties.created_at.isNullOrBlank()}",
                                )
                                val result = withContext(Dispatchers.IO) {
                                    if (dbId != null) {
                                        repo.updatePlace(dbId, updated)
                                    } else {
                                        repo.createPlace(updated)
                                    }
                                }
                                if (result.isSuccess) {
                                    GeoVaultCaptureLog.i(
                                        TAG,
                                        "save online-ok name=${updated.properties.name} " +
                                            "serverId=${result.getOrNull()?.properties?.database_id}",
                                    )
                                    setResult(
                                        RESULT_OK,
                                        Intent().putExtra(EXTRA_UPDATED_FEATURE, result.getOrNull()),
                                    )
                                    finish()
                                    return@launch
                                }

                                val error = result.exceptionOrNull()!!
                                val kind = GeoVaultHttpFailureClassifier.classifyThrowable(error)
                                val disposition = GeoVaultQueuedSyncFailurePolicy.dispositionFor(kind)
                                GeoVaultCaptureLog.e(
                                    TAG,
                                    "save online-failed name=${updated.properties.name} " +
                                        "kind=$kind disposition=$disposition " +
                                        "error=${error.message}",
                                )
                                when {
                                    disposition == GeoVaultQueuedSyncItemDisposition.RequireAuth -> {
                                        snackbarMessage = PlacesOfflineBehaviorPolicy.AUTH_REQUIRED_MESSAGE
                                        saveInFlight.set(false)
                                    }
                                    disposition == GeoVaultQueuedSyncItemDisposition.DropAndSurface -> {
                                        snackbarMessage = error.message?.takeIf { it.isNotBlank() }
                                            ?: PlacesOfflineBehaviorPolicy.VALIDATION_FAILED_MESSAGE
                                        saveInFlight.set(false)
                                    }
                                    GeoVaultQueuedSyncFailurePolicy.shouldFallbackToOfflineSave(kind) -> {
                                        val snackbar = when (kind) {
                                            GeoVaultHttpFailureKind.RetryableNetwork,
                                            GeoVaultHttpFailureKind.RetryableServer,
                                            GeoVaultHttpFailureKind.Unknown ->
                                                PlacesOfflineBehaviorPolicy.SAVED_OFFLINE_NETWORK_MESSAGE
                                            else -> PlacesOfflineBehaviorPolicy.SAVED_OFFLINE_MESSAGE
                                        }
                                        setResult(
                                            RESULT_OK,
                                            Intent().apply {
                                                putExtra(EXTRA_OFFLINE_FEATURE, updated)
                                                putExtra(EXTRA_ORIGINAL_FEATURE, editFeature)
                                                putExtra(EXTRA_CLIENT_LOCAL_ID, clientLocalId)
                                                putExtra(EXTRA_OFFLINE_SNACKBAR, snackbar)
                                            },
                                        )
                                        finish()
                                    }
                                }
                            } catch (t: Throwable) {
                                saveInFlight.set(false)
                                throw t
                            }
                        }
                    },
                )
                GeoVaultAppSnackbarLayer(
                    snackbar = snackbarMessage?.let { message ->
                        GeoVaultSnackbarModel(id = message, message = message)
                    },
                    onDismissSnackbar = { snackbarMessage = null },
                    update = null,
                    onDismissUpdate = {},
                )
                }
            }
        }
    }
}

@Composable
private fun PlaceEditScreen(
    initial: Feature?,
    isOfflineEdit: Boolean,
    onClose: () -> Unit,
    onDeleteOrRevert: () -> Unit,
    onSave: (Feature) -> Unit,
) {
    val state = remember(initial, isOfflineEdit) { PlaceEditScreenState(initial = initial, isOfflineEdit = isOfflineEdit) }
    val map = rememberGeoVaultStandardMap()
    val context = androidx.compose.ui.platform.LocalContext.current
    val geocodingRepository = remember(context) { GeocodingRepository(context) }
    var showGeocodeSearchDialog by remember { mutableStateOf(false) }
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
    val gpsOneShotController = rememberGeoVaultGpsOneShotMyLocationController(
        map = map,
        userLocation = locationPlugin,
        onLocationResolved = { latLng ->
            state.setFromDeviceLocation(latLng.latitude, latLng.longitude)
        },
        showUserLocationPuck = false,
    )
    val layerFabAction = remember(map) { geoVaultLayerToggleFabAction(map, order = 1) }
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
            if (state.setFromMapPoint(clicked.latitude, clicked.longitude)) {
                dismissInputFocus()
            }
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
        val coordinateValid = isValidMapLibreGeographicLatLng(lat, lon)
        val points = if (state.showSelectedPointMarker && coordinateValid) {
            listOf(
                MapRenderPoint(
                    id = "edit-selected-point",
                    latitude = lat,
                    longitude = lon,
                    iconImageId = CommonMapIconIds.MARKER_DEFAULT,
                    iconSize = 1f,
                ),
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
        if (coordinateValid) {
            map.animateCameraWithPadding(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(lat, lon),
                    MapLibreManager.DEFAULT_POINT_ZOOM,
                ),
            )
        }
        state.markSelectionCameraFocusHandled()
    }

    Scaffold(
        backgroundColor = MaterialTheme.colors.background,
        topBar = {
            GeoVaultTopTitleBar(
                title = state.title,
                backgroundColor = GeoVaultColorTokens.MainBlue,
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
                .background(MaterialTheme.colors.background),
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
                                contentDescription = "Search for coordinates",
                                onTap = {
                                    dismissInputFocus()
                                    showGeocodeSearchDialog = true
                                },
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
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = formMinHeight)
                        .heightIn(max = formMaxHeight),
                    shape = androidx.compose.ui.graphics.RectangleShape,
                    color = MaterialTheme.colors.surface,
                    elevation = 0.dp,
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Divider(
                            modifier = Modifier.fillMaxWidth(),
                            color = geoVaultHairlineDividerColor(),
                            thickness = 1.dp,
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .geoVaultKeyboardAwareVerticalScroll(formScrollState)
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("Name *", color = geoVaultContentSecondaryColor(), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            GeoVaultInput(
                                value = state.name,
                                onValueChange = { state.name = it },
                                label = null,
                                placeholder = "Place name",
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Words,
                                ),
                            )

                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Description", color = geoVaultContentSecondaryColor(), fontSize = 14.sp, fontWeight = FontWeight.Bold)
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
                                    "Coordinates *",
                                    modifier = Modifier.weight(1f),
                                    color = geoVaultContentSecondaryColor(),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                state.coordinatesError?.let {
                                    Text(it, color = GeoVaultColorTokens.Error, fontSize = 12.sp)
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Min),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                GeoVaultInput(
                                    value = state.coordinatesInput,
                                    onValueChange = state::onCoordinatesEdited,
                                    label = null,
                                    placeholder = "latitude, longitude",
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                GeoVaultSecondaryButton(
                                    text = "{ }",
                                    onClick = { state.parseCoordinatesFromInput() },
                                    tooltip = stringResource(R.string.tooltip_place_normalize_coordinates),
                                    fitToContent = true,
                                    modifier = Modifier.fillMaxHeight(),
                                )
                            }

                            GeoVaultSecondaryButton(
                                text = "Use my location",
                                onClick = {
                                    dismissInputFocus()
                                    gpsOneShotController.onJumpToMyLocation()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !gpsOneShotController.isWaitingForFix,
                                tooltip = stringResource(R.string.tooltip_place_use_my_location),
                                centeredContent = {
                                    val locationButtonTint = LocalContentColor.current
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        if (gpsOneShotController.isWaitingForFix) {
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
                                    tooltip = stringResource(R.string.tooltip_place_save),
                                    modifier = Modifier.weight(1f),
                                )
                                GeoVaultSecondaryButton(
                                    text = "Cancel",
                                    onClick = {
                                        if (state.hasUnsavedChanges) state.showDiscardDialog = true else onClose()
                                    },
                                    tooltip = stringResource(R.string.tooltip_place_cancel),
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

    if (showGeocodeSearchDialog) {
        GeoVaultMapGeocodeSearchDialog(
            visible = true,
            repository = geocodingRepository,
            onDismissRequest = { showGeocodeSearchDialog = false },
            onPickResult = state::setFromSearchResult,
        )
    }
}
