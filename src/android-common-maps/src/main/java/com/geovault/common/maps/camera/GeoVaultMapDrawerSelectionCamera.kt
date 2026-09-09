package com.geovault.common.maps.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.geovault.common.maps.ui.scaffold.GeoVaultMapDrawerAnchor
import com.geovault.common.maps.ui.scaffold.GeoVaultMapDrawerState
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import androidx.compose.runtime.snapshotFlow

/**
 * Shared tap / list camera choreography for maps that pair a bottom drawer with a selection.
 *
 * Map taps open the sheet to half-expanded and, when the point would sit under that sheet,
 * mirror-pan the camera with the live drawer height. List picks either zoom to the drawer
 * point ([ListFocus.ZoomToDrawerPoint]) or keep the current zoom ([ListFocus.PreserveZoom]).
 */
class GeoVaultMapDrawerSelectionCamera(
    private val camera: GeoVaultMapCameraController,
    private val drawerState: GeoVaultMapDrawerState,
    private val scope: CoroutineScope,
    private val runProgrammaticCamera: (() -> Unit) -> Unit,
    private val edgeMarginPx: Int,
) {
    enum class ListFocus {
        ZoomToDrawerPoint,
        PreserveZoom,
    }

    fun onMapTap(
        latitude: Double,
        longitude: Double,
        afterSelect: () -> Unit = {},
    ) {
        runProgrammaticCamera { }
        afterSelect()
        val settledHalfHeightPx = drawerState.halfExpandedSettledVisibleHeightPx()
        val needsMirrorPan = settledHalfHeightPx > 0 &&
            camera.selectionWouldBeObscuredByDrawer(
                latitude = latitude,
                longitude = longitude,
                drawerSettledReservePx = settledHalfHeightPx,
                edgeMarginPx = edgeMarginPx,
            )
        scope.launch {
            launch { drawerState.animateTo(GeoVaultMapDrawerAnchor.HalfExpanded) }
            if (needsMirrorPan) {
                launch {
                    snapshotFlow { drawerState.visibleHeightPx.value }
                        .transformWhile { live ->
                            emit(live)
                            abs(live - settledHalfHeightPx) > 1
                        }
                        .collect { liveDrawerHeight ->
                            camera.mirrorPanToDrawerHeight(
                                latitude = latitude,
                                longitude = longitude,
                                drawerReservePx = liveDrawerHeight,
                            )
                        }
                }
            }
        }
    }

    fun onListSelect(
        latitude: Double,
        longitude: Double,
        focus: ListFocus,
        afterSelect: () -> Unit = {},
    ) {
        runProgrammaticCamera { }
        afterSelect()
        val isExpanded = drawerState.currentAnchor == GeoVaultMapDrawerAnchor.Expanded
        val settledHalfHeightPx = drawerState.halfExpandedSettledVisibleHeightPx()
        when (focus) {
            ListFocus.ZoomToDrawerPoint -> {
                val initialReservePx = if (isExpanded && settledHalfHeightPx > 0) {
                    settledHalfHeightPx
                } else {
                    drawerState.visibleHeightPx.value
                }
                camera.focusOnSelectionFromDrawer(
                    latitude = latitude,
                    longitude = longitude,
                    drawerReservePx = initialReservePx,
                )
            }
            ListFocus.PreserveZoom -> {
                camera.focusPoint(latitude, longitude)
            }
        }
        if (isExpanded) {
            val needsMirrorPan = focus == ListFocus.ZoomToDrawerPoint ||
                (
                    settledHalfHeightPx > 0 &&
                        camera.selectionWouldBeObscuredByDrawer(
                            latitude = latitude,
                            longitude = longitude,
                            drawerSettledReservePx = settledHalfHeightPx,
                            edgeMarginPx = edgeMarginPx,
                        )
                    )
            scope.launch {
                launch { drawerState.animateTo(GeoVaultMapDrawerAnchor.HalfExpanded) }
                if (settledHalfHeightPx > 0 && needsMirrorPan) {
                    launch {
                        snapshotFlow { drawerState.visibleHeightPx.value }
                            .transformWhile { live ->
                                emit(live)
                                abs(live - settledHalfHeightPx) > 1
                            }
                            .collect { liveDrawerHeight ->
                                camera.mirrorPanToDrawerHeight(
                                    latitude = latitude,
                                    longitude = longitude,
                                    drawerReservePx = liveDrawerHeight,
                                )
                            }
                    }
                }
            }
        } else if (focus == ListFocus.PreserveZoom) {
            camera.panIfObscuredByDrawer(
                latitude = latitude,
                longitude = longitude,
                drawerReservePx = drawerState.visibleHeightPx.value,
                edgeMarginPx = edgeMarginPx,
            )
        }
    }
}

/**
 * Post-drag safety net: when the settled drawer would cover the current selection, nudge
 * the map. Keyed on the settled anchor — not the selection — so a new pick does not re-fire
 * this path (tap / list handlers own that camera move).
 */
@Composable
fun GeoVaultMapDrawerSelectionObscurePanEffect(
    camera: GeoVaultMapCameraController,
    drawerState: GeoVaultMapDrawerState,
    selectionLatitude: Double?,
    selectionLongitude: Double?,
    edgeMarginPx: Int,
) {
    val settledDrawerAnchor = drawerState.currentAnchor
    LaunchedEffect(settledDrawerAnchor, edgeMarginPx) {
        val lat = selectionLatitude ?: return@LaunchedEffect
        val lon = selectionLongitude ?: return@LaunchedEffect
        camera.panIfObscuredByDrawer(
            latitude = lat,
            longitude = lon,
            drawerReservePx = drawerState.visibleHeightPx.value,
            edgeMarginPx = edgeMarginPx,
        )
    }
}
