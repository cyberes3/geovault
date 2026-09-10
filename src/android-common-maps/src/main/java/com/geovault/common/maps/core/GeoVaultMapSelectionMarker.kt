package com.geovault.common.maps.core

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import com.geovault.common.maps.render.GeoJsonSelectionMarkerState
import com.geovault.common.maps.render.GeoJsonSelectionMarkerVisual
import com.geovault.common.maps.render.GeoJsonSelectionOverlayLayout
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

/**
 * Selected-point marker as a Compose sibling of [org.maplibre.android.maps.MapView].
 *
 * The marker [state] and the camera projection are read inside the [Canvas] draw lambda, so a
 * new selection or a camera move invalidates only the draw phase (no recomposition) and the
 * marker follows the map as it moves. No pointer input, so map taps still reach MapLibre.
 */
@Composable
internal fun GeoVaultMapSelectionMarkerLayer(
    map: GeoVaultBaseMap,
    state: GeoJsonSelectionMarkerState?,
) {
    if (state == null) return
    val attachmentVersion by map.mapAttachmentVersion.collectAsState()

    // Bumped by camera callbacks; read in the draw lambda to redraw the marker as the map moves.
    var cameraTick by remember { mutableIntStateOf(0) }
    val bitmapHolder = remember { MarkerBitmapHolder() }

    DisposableEffect(map, attachmentVersion) {
        val mapLibreMap = map.maplibreMap ?: return@DisposableEffect onDispose { }
        val moveListener = MapLibreMap.OnCameraMoveListener { cameraTick++ }
        val idleListener = MapLibreMap.OnCameraIdleListener { cameraTick++ }
        mapLibreMap.addOnCameraMoveListener(moveListener)
        mapLibreMap.addOnCameraIdleListener(idleListener)
        onDispose {
            mapLibreMap.removeOnCameraMoveListener(moveListener)
            mapLibreMap.removeOnCameraIdleListener(idleListener)
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        @Suppress("UNUSED_EXPRESSION")
        cameraTick
        val visual = state.visual ?: return@Canvas
        val mapLibreMap = map.maplibreMap ?: return@Canvas
        val image = bitmapHolder.imageFor(visual)
        drawSelectionMarker(visual, image, mapLibreMap.selectionMarkerOffset(visual))
    }
}

private class MarkerBitmapHolder {
    private var key: Int = 0
    private var cached: ImageBitmap? = null

    fun imageFor(visual: GeoJsonSelectionMarkerVisual): ImageBitmap {
        val newKey = System.identityHashCode(visual.bitmap)
        val existing = cached
        if (existing != null && key == newKey) return existing
        return visual.bitmap.asImageBitmap().also {
            key = newKey
            cached = it
        }
    }
}

private fun MapLibreMap.selectionMarkerOffset(visual: GeoJsonSelectionMarkerVisual): Offset {
    val screen = projection.toScreenLocation(LatLng(visual.latitude, visual.longitude))
    return Offset(screen.x, screen.y)
}

private fun DrawScope.drawSelectionMarker(
    visual: GeoJsonSelectionMarkerVisual,
    image: ImageBitmap,
    screenOffset: Offset,
) {
    val width = image.width.toFloat()
    val height = image.height.toFloat()
    val placement = GeoJsonSelectionOverlayLayout.placement(
        screenX = screenOffset.x,
        screenY = screenOffset.y,
        width = width,
        height = height,
        iconAnchor = visual.iconAnchor,
    )
    val pivot = Offset(placement.pivotX, placement.pivotY)
    translate(placement.translationX, placement.translationY) {
        rotate(visual.iconRotationDegrees, pivot) {
            scale(scaleX = visual.iconSize, scaleY = visual.iconSize, pivot = pivot) {
                drawImage(image)
            }
        }
    }
}
