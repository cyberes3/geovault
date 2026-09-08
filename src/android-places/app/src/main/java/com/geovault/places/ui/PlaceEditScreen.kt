package com.geovault.places.ui

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
import androidx.compose.material.Icon
import androidx.compose.material.LocalContentColor
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geovault.common.maps.core.GeoVaultMapPhase
import com.geovault.common.maps.core.GeoVaultStandardMapView
import com.geovault.common.maps.core.MapLibreManager
import com.geovault.common.maps.core.isValidMapLibreGeographicLatLng
import com.geovault.common.maps.core.rememberGeoVaultStandardMap
import com.geovault.common.maps.geocoding.GeocodingRepository
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
import com.geovault.common.maps.ui.geocoding.GeoVaultMapGeocodeSearchDialog
import com.geovault.common.maps.ui.oneshot.rememberGeoVaultGpsOneShotMyLocationController
import com.geovault.common.ui.components.GeoVaultConfirmationDialog
import com.geovault.common.ui.components.GeoVaultFormSection
import com.geovault.common.ui.components.GeoVaultFormSectionHeader
import com.geovault.common.ui.components.GeoVaultInput
import com.geovault.common.ui.components.GeoVaultLoadingSpinner
import com.geovault.common.ui.components.GeoVaultPrimaryButton
import com.geovault.common.ui.components.GeoVaultRequestBottomTabsHidden
import com.geovault.common.ui.components.GeoVaultSecondaryButton
import com.geovault.common.ui.components.GeoVaultTopTitleBar
import com.geovault.common.ui.components.GeoVaultTopTitleBarDefaults
import com.geovault.common.ui.components.TopBarIconAction
import com.geovault.common.ui.modifier.geoVaultKeyboardAwareVerticalScroll
import com.geovault.common.ui.navigation.GeoVaultRegisterBackHandler
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.common.ui.theme.geoVaultHairlineDividerColor
import com.geovault.places.R
import com.geovault.places.presentation.PlaceEditCameraMotion
import com.geovault.places.presentation.PlaceEditEvent
import com.geovault.places.presentation.PlaceEditFormDraft
import com.geovault.places.presentation.PlaceEditMode
import com.geovault.places.presentation.PlaceEditViewModel
import com.geovault.places.presentation.PlacesOfflineBehaviorPolicy
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

@Composable
fun PlaceEditScreen(
    viewModel: PlaceEditViewModel,
    onClose: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var formDraft by rememberSaveable(stateSaver = PlaceEditFormDraftSaver) {
        mutableStateOf(PlaceEditFormDraft.from(state))
    }
    var appliedSavedDraft by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!appliedSavedDraft) {
            viewModel.restoreDraft(formDraft)
            appliedSavedDraft = true
        }
    }
    LaunchedEffect(state) {
        if (appliedSavedDraft) {
            formDraft = PlaceEditFormDraft.from(state)
        }
    }
    val map = rememberGeoVaultStandardMap()
    val context = LocalContext.current
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
                synchronousGeoJsonApplication = true,
            ),
            context = context,
        )
    }
    val locationPlugin = rememberGeoVaultMapUserLocationPlugin(context = context)
    val gpsOneShotController = rememberGeoVaultGpsOneShotMyLocationController(
        map = map,
        userLocation = locationPlugin,
        onLocationResolved = { latLng ->
            viewModel.setFromDeviceLocation(latLng.latitude, latLng.longitude)
        },
        showUserLocationPuck = false,
    )
    val layerFabAction = remember(map) { geoVaultLayerToggleFabAction(map, order = 1) }
    val formScrollState = rememberScrollState()

    GeoVaultRequestBottomTabsHidden(shouldHide = true)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                PlaceEditEvent.Closed -> onClose()
                is PlaceEditEvent.Message -> onMessage(event.text)
            }
        }
    }

    GeoVaultRegisterBackHandler(
        canGoBack = { true },
        onBack = {
            if (state.hasUnsavedChanges) {
                viewModel.setShowDiscardDialog(true)
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
            if (viewModel.setFromMapPoint(clicked.latitude, clicked.longitude)) {
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
    LaunchedEffect(state.selectedLat, state.selectedLon, phase, state.showSelectedPointMarker) {
        val lat = state.selectedLat
        val lon = state.selectedLon
        val coordinateValid = lat != null && lon != null && isValidMapLibreGeographicLatLng(lat, lon)
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
        renderPlugin.setRenderState(MapRenderState(points = points))
        if (phase != GeoVaultMapPhase.Ready) return@LaunchedEffect
        if (state.pendingCameraMotion != PlaceEditCameraMotion.FocusSelection) return@LaunchedEffect
        if (coordinateValid) {
            map.animateCameraWithPadding(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(lat, lon),
                    MapLibreManager.DEFAULT_POINT_ZOOM,
                ),
            )
        }
        viewModel.markSelectionCameraFocusHandled()
    }

    Scaffold(
        backgroundColor = MaterialTheme.colors.background,
        topBar = {
            GeoVaultTopTitleBar(
                title = state.title,
                backgroundColor = GeoVaultColorTokens.MainBlue,
                rightActions = if (state.mode != PlaceEditMode.New) {
                    listOf(
                        TopBarIconAction(
                            icon = Icons.Filled.Delete,
                            contentDescription = PlacesOfflineBehaviorPolicy.destructiveActionLabel(state.deleteAction),
                            onClick = { viewModel.setShowDeleteDialog(true) },
                        ),
                        GeoVaultTopTitleBarDefaults.closeAction(
                            onClick = {
                                if (state.hasUnsavedChanges) {
                                    viewModel.setShowDiscardDialog(true)
                                } else {
                                    onClose()
                                }
                            },
                        ),
                    )
                } else {
                    listOf(
                        GeoVaultTopTitleBarDefaults.closeAction(
                            onClick = {
                                if (state.hasUnsavedChanges) {
                                    viewModel.setShowDiscardDialog(true)
                                } else {
                                    onClose()
                                }
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
                        GeoVaultFormSection(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .geoVaultKeyboardAwareVerticalScroll(formScrollState)
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                        ) {
                            GeoVaultFormSectionHeader("Name *")
                            GeoVaultInput(
                                value = state.name,
                                onValueChange = viewModel::onNameChange,
                                label = null,
                                placeholder = "Place name",
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Words,
                                ),
                            )

                            GeoVaultFormSectionHeader("Description")
                            GeoVaultInput(
                                value = state.description,
                                onValueChange = viewModel::onDescriptionChange,
                                label = null,
                                placeholder = "Optional description",
                                singleLine = false,
                                modifier = Modifier.fillMaxWidth(),
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                GeoVaultFormSectionHeader(
                                    "Coordinates *",
                                    modifier = Modifier.weight(1f),
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
                                    onValueChange = viewModel::onCoordinatesEdited,
                                    label = null,
                                    placeholder = "latitude, longitude",
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                GeoVaultSecondaryButton(
                                    text = "{ }",
                                    onClick = viewModel::parseCoordinatesFromInput,
                                    tooltip = stringResource(R.string.tooltip_place_normalize_coordinates),
                                    fitToContent = true,
                                    modifier = Modifier.fillMaxHeight(),
                                )
                            }

                            GeoVaultFormSectionHeader("Address")
                            GeoVaultInput(
                                value = state.selectedAddress.orEmpty(),
                                onValueChange = viewModel::onAddressChange,
                                label = null,
                                placeholder = "Optional address",
                                modifier = Modifier.fillMaxWidth(),
                            )

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
                                    onClick = viewModel::save,
                                    enabled = state.isSaveEnabled,
                                    tooltip = stringResource(R.string.tooltip_place_save),
                                    modifier = Modifier.weight(1f),
                                )
                                GeoVaultSecondaryButton(
                                    text = "Cancel",
                                    onClick = {
                                        if (state.hasUnsavedChanges) {
                                            viewModel.setShowDiscardDialog(true)
                                        } else {
                                            onClose()
                                        }
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
                viewModel.setShowDiscardDialog(false)
                onClose()
            },
            onCancel = { viewModel.setShowDiscardDialog(false) },
            confirmText = "Discard",
            cancelText = "Cancel",
        )
    }

    if (state.showDeleteDialog && state.mode != PlaceEditMode.New) {
        val actionLabel = PlacesOfflineBehaviorPolicy.destructiveActionLabel(state.deleteAction)
        val placeName = state.name.ifBlank { "this place" }
        val message = when (state.mode) {
            PlaceEditMode.EditPendingUpdate ->
                "Are you sure you want to revert your changes to '$placeName'?"
            PlaceEditMode.EditPendingCreate ->
                "Are you sure you want to discard '$placeName'?"
            else ->
                "Are you sure you want to delete '$placeName'? This cannot be undone."
        }
        GeoVaultConfirmationDialog(
            title = "$actionLabel Place",
            message = message,
            onConfirm = {
                viewModel.setShowDeleteDialog(false)
                viewModel.deleteOrRevert()
            },
            onCancel = { viewModel.setShowDeleteDialog(false) },
            confirmText = actionLabel,
            cancelText = "Cancel",
        )
    }

    if (showGeocodeSearchDialog) {
        GeoVaultMapGeocodeSearchDialog(
            visible = true,
            repository = geocodingRepository,
            onDismissRequest = { showGeocodeSearchDialog = false },
            onPickResult = viewModel::setFromSearchResult,
        )
    }
}

private val PlaceEditFormDraftSaver = listSaver<PlaceEditFormDraft, String>(
    save = { draft ->
        listOf(
            draft.name,
            draft.description,
            draft.coordinatesInput,
            draft.address.orEmpty(),
            draft.selectedLat?.toString().orEmpty(),
            draft.selectedLon?.toString().orEmpty(),
        )
    },
    restore = { values ->
        PlaceEditFormDraft(
            name = values.getOrElse(0) { "" },
            description = values.getOrElse(1) { "" },
            coordinatesInput = values.getOrElse(2) { "" },
            address = values.getOrNull(3)?.takeIf { it.isNotEmpty() },
            selectedLat = values.getOrNull(4)?.toDoubleOrNull(),
            selectedLon = values.getOrNull(5)?.toDoubleOrNull(),
        )
    },
)
