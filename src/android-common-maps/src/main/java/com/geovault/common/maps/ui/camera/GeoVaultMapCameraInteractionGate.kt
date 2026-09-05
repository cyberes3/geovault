package com.geovault.common.maps.ui.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.geovault.common.maps.core.GeoVaultBaseMap
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.gestures.RotateGestureDetector
import org.maplibre.android.gestures.StandardScaleGestureDetector
import org.maplibre.android.maps.MapLibreMap

enum class GeoVaultMapCameraInteraction {
    Pan,
    Fling,
    Rotate,
    PinchZoom,
    ProgrammaticZoom,
}

/**
 * Classifies map camera interactions so hosts can keep follow/selection lock across zoom and
 * release it only when the user pans, flings, or rotates.
 */
object GeoVaultMapCameraInteractionGate {
    fun unlocksCamera(interaction: GeoVaultMapCameraInteraction): Boolean {
        return when (interaction) {
            GeoVaultMapCameraInteraction.Pan,
            GeoVaultMapCameraInteraction.Fling,
            GeoVaultMapCameraInteraction.Rotate -> true
            GeoVaultMapCameraInteraction.PinchZoom,
            GeoVaultMapCameraInteraction.ProgrammaticZoom -> false
        }
    }

    fun ownsZoom(interaction: GeoVaultMapCameraInteraction): Boolean {
        return when (interaction) {
            GeoVaultMapCameraInteraction.PinchZoom,
            GeoVaultMapCameraInteraction.ProgrammaticZoom -> true
            GeoVaultMapCameraInteraction.Pan,
            GeoVaultMapCameraInteraction.Fling,
            GeoVaultMapCameraInteraction.Rotate -> false
        }
    }
}

/**
 * Listens to MapLibre move / fling / rotate / scale, not [MapLibreMap.OnCameraMoveStartedListener]
 * reasons. Pinch and FAB zoom are scale or programmatic; they must not be treated as a camera
 * takeover.
 */
@Composable
fun GeoVaultMapCameraInteractionEffect(
    map: GeoVaultBaseMap,
    onCameraTakeover: () -> Unit,
    onUserOwnedZoom: () -> Unit,
) {
    val attachmentVersion by map.mapAttachmentVersion.collectAsState()
    val takeover = rememberUpdatedState(onCameraTakeover)
    val ownedZoom = rememberUpdatedState(onUserOwnedZoom)
    DisposableEffect(map, attachmentVersion) {
        val mapLibre = map.maplibreMap ?: return@DisposableEffect onDispose { }
        fun dispatch(interaction: GeoVaultMapCameraInteraction) {
            if (GeoVaultMapCameraInteractionGate.unlocksCamera(interaction)) {
                takeover.value()
            }
            if (GeoVaultMapCameraInteractionGate.ownsZoom(interaction)) {
                ownedZoom.value()
            }
        }
        val moveListener = object : MapLibreMap.OnMoveListener {
            override fun onMoveBegin(detector: MoveGestureDetector) {
                dispatch(GeoVaultMapCameraInteraction.Pan)
            }

            override fun onMove(detector: MoveGestureDetector) = Unit

            override fun onMoveEnd(detector: MoveGestureDetector) = Unit
        }
        val flingListener = MapLibreMap.OnFlingListener {
            dispatch(GeoVaultMapCameraInteraction.Fling)
        }
        val rotateListener = object : MapLibreMap.OnRotateListener {
            override fun onRotateBegin(detector: RotateGestureDetector) {
                dispatch(GeoVaultMapCameraInteraction.Rotate)
            }

            override fun onRotate(detector: RotateGestureDetector) = Unit

            override fun onRotateEnd(detector: RotateGestureDetector) = Unit
        }
        val scaleListener = object : MapLibreMap.OnScaleListener {
            override fun onScaleBegin(detector: StandardScaleGestureDetector) {
                dispatch(GeoVaultMapCameraInteraction.PinchZoom)
            }

            override fun onScale(detector: StandardScaleGestureDetector) = Unit

            override fun onScaleEnd(detector: StandardScaleGestureDetector) = Unit
        }
        mapLibre.addOnMoveListener(moveListener)
        mapLibre.addOnFlingListener(flingListener)
        mapLibre.addOnRotateListener(rotateListener)
        mapLibre.addOnScaleListener(scaleListener)
        onDispose {
            mapLibre.removeOnMoveListener(moveListener)
            mapLibre.removeOnFlingListener(flingListener)
            mapLibre.removeOnRotateListener(rotateListener)
            mapLibre.removeOnScaleListener(scaleListener)
        }
    }
}
