package com.geovault.common.maps.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds

private val preloadHandler = Handler(Looper.getMainLooper())

sealed interface GeoVaultMainMapPreloadCameraTarget {
    data object None : GeoVaultMainMapPreloadCameraTarget
    data class Single(val lat: Double, val lon: Double) : GeoVaultMainMapPreloadCameraTarget
    data class Bounds(val bounds: LatLngBounds) : GeoVaultMainMapPreloadCameraTarget
}

object GeoVaultMainMapControllerStore {
    private val lock = Any()
    private val controllers = linkedMapOf<String, GeoVaultMapController>()

    fun getOrCreate(context: Context, key: String): GeoVaultMapController {
        synchronized(lock) {
            return controllers.getOrPut(key) {
                GeoVaultMapController(context.applicationContext)
            }
        }
    }

    fun clear(key: String) {
        synchronized(lock) {
            controllers.remove(key)?.onDestroy()
        }
    }
}

@Composable
fun rememberGeoVaultMainMapController(
    key: String,
): GeoVaultMapController {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(context, key) {
        GeoVaultMainMapControllerStore.getOrCreate(context.applicationContext, key)
    }
}

fun preloadGeoVaultMainMapOnAppLaunch(
    context: Context,
    key: String,
) {
    val appContext = context.applicationContext
    preloadHandler.post {
        GeoVaultMainMapControllerStore.getOrCreate(appContext, key).preloadMainMap()
    }
}

fun resolveGeoVaultMainMapPreloadCameraTarget(
    points: List<LatLng>,
): GeoVaultMainMapPreloadCameraTarget {
    if (points.isEmpty()) return GeoVaultMainMapPreloadCameraTarget.None
    if (points.size == 1) {
        val point = points.first()
        return GeoVaultMainMapPreloadCameraTarget.Single(point.latitude, point.longitude)
    }
    return GeoVaultMainMapPreloadCameraTarget.Bounds(
        LatLngBounds.Builder().includes(points).build(),
    )
}

@Composable
fun GeoVaultMainMapPreloadHost(
    mainMapKey: String,
    enabled: Boolean,
    cameraTarget: GeoVaultMainMapPreloadCameraTarget,
    modifier: Modifier = Modifier.fillMaxSize(),
    boundsPaddingPx: Int = 96,
) {
    if (!enabled) return
    val controller = rememberGeoVaultMainMapController(mainMapKey)
    val phase by controller.phase.collectAsState()

    LaunchedEffect(phase, cameraTarget, enabled) {
        if (!enabled) return@LaunchedEffect
        if (phase != GeoVaultMapPhase.Ready) return@LaunchedEffect
        when (cameraTarget) {
            is GeoVaultMainMapPreloadCameraTarget.Single -> {
                controller.moveCameraWithPadding(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(cameraTarget.lat, cameraTarget.lon),
                        MapLibreManager.DEFAULT_POINT_ZOOM,
                    ),
                )
            }

            is GeoVaultMainMapPreloadCameraTarget.Bounds -> {
                controller.moveCameraWithPadding(
                    CameraUpdateFactory.newLatLngBounds(cameraTarget.bounds, boundsPaddingPx),
                )
            }

            GeoVaultMainMapPreloadCameraTarget.None -> Unit
        }
    }

    GeoVaultMap(
        modifier = modifier,
        controller = controller,
        showDefaultSourceToggle = false,
        mapMode = GeoVaultMapMode.Main,
    )
}
