package com.geovault.common.maps.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds

sealed interface GeoVaultMainMapPreloadCameraTarget {
    data object None : GeoVaultMainMapPreloadCameraTarget
    data class Single(val lat: Double, val lon: Double) : GeoVaultMainMapPreloadCameraTarget
    data class Bounds(val bounds: LatLngBounds) : GeoVaultMainMapPreloadCameraTarget
}

internal interface MainMapControllerRef {
    val map: GeoVaultMainMap
    fun preload()
    fun destroy()
}

private class DefaultMainMapControllerRef(
    context: Context,
) : MainMapControllerRef {
    override val map: GeoVaultMainMap = GeoVaultMainMap(context.applicationContext)

    override fun preload() {
        map.preload()
    }

    override fun destroy() {
        map.onDestroy()
    }
}

object GeoVaultMainMapControllerStore {
    private val lock = Any()
    private val controllers = linkedMapOf<String, MainMapControllerRef>()

    fun acquire(context: Context, key: String): GeoVaultMainMap {
        return getOrCreateRef(context.applicationContext, key).map
    }

    /**
     * Deterministic teardown for reset/auth-flush flows.
     * Main maps are app-scoped registry entries and are not released by Compose consumers.
     */
    fun releaseKey(key: String) {
        val refToDestroy = synchronized(lock) {
            controllers.remove(key)
        }
        refToDestroy?.destroy()
    }

    fun releaseAll() {
        val refsToDestroy = mutableListOf<MainMapControllerRef>()
        synchronized(lock) {
            refsToDestroy.addAll(controllers.values)
            controllers.clear()
        }
        refsToDestroy.forEach { ref ->
            ref.destroy()
        }
    }

    /**
     * Invokes [block] with every currently-registered main map (regardless of refcount). Used
     * by host [android.app.Activity]s to forward system callbacks like `onLowMemory` to each
     * retained `MapView` without exposing the internal store map. Safe to call from the main
     * thread while composition is running — the snapshot is taken under the store lock.
     */
    fun forEachActiveMap(block: (GeoVaultMainMap) -> Unit) {
        val snapshot = synchronized(lock) { controllers.values.map { it.map } }
        snapshot.forEach(block)
    }

    fun preload(context: Context, key: String) {
        val ref = getOrCreateRef(context.applicationContext, key)
        ref.preload()
    }

    @VisibleForTesting
    internal fun currentKeyCountForTest(): Int {
        return synchronized(lock) { controllers.size }
    }

    @VisibleForTesting
    internal fun preloadForTest(
        key: String,
        factory: () -> MainMapControllerRef,
    ) {
        getOrCreateRefForTest(key, factory).preload()
    }

    @VisibleForTesting
    internal fun resetForTest() {
        releaseAll()
    }

    private fun getOrCreateRef(context: Context, key: String): MainMapControllerRef {
        return synchronized(lock) {
            controllers.getOrPut(key) {
                DefaultMainMapControllerRef(context.applicationContext)
            }
        }
    }

    @VisibleForTesting
    internal fun getOrCreateRefForTest(
        key: String,
        factory: () -> MainMapControllerRef,
    ): MainMapControllerRef {
        return synchronized(lock) {
            controllers.getOrPut(key) { factory() }
        }
    }
}

@Composable
fun rememberGeoVaultMainMap(
    key: String,
): GeoVaultMainMap {
    val context = LocalContext.current
    return remember(context, key) {
        GeoVaultMainMapControllerStore.acquire(context.applicationContext, key)
    }
}

fun preloadGeoVaultMainMapOnAppLaunch(
    context: Context,
    key: String,
) {
    val preloadHandler = Handler(Looper.getMainLooper())
    val appContext = context.applicationContext
    preloadHandler.post {
        GeoVaultMainMapControllerStore.preload(appContext, key)
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
    val bounds = geoVaultLatLngBoundsForPoints(points) ?: return GeoVaultMainMapPreloadCameraTarget.None
    return GeoVaultMainMapPreloadCameraTarget.Bounds(bounds)
}

/**
 * Hosts the shared main map for preload/warm-up. When [surfaceMapInHost] is false, the [GeoVaultMainMapView]
 * is not composed here so another screen can own the sole MapLibre AndroidView for the same controller key
 * (avoids two views fighting for one MapLibre surface).
 */
@Composable
fun GeoVaultMainMapPreloadHost(
    mainMapKey: String,
    enabled: Boolean,
    cameraTarget: GeoVaultMainMapPreloadCameraTarget,
    modifier: Modifier = Modifier.fillMaxSize(),
    surfaceMapInHost: Boolean = true,
) {
    if (!enabled) {
        return
    }
    GeoVaultMainMapPreloadHostAuthenticatedBody(
        mainMapKey = mainMapKey,
        cameraTarget = cameraTarget,
        modifier = modifier,
        surfaceMapInHost = surfaceMapInHost,
    )
}

@Composable
private fun GeoVaultMainMapPreloadHostAuthenticatedBody(
    mainMapKey: String,
    cameraTarget: GeoVaultMainMapPreloadCameraTarget,
    modifier: Modifier,
    surfaceMapInHost: Boolean,
) {
    val map = rememberGeoVaultMainMap(mainMapKey)
    val phase by map.phase.collectAsState()
    val density = LocalDensity.current
    val preloadPaddingPolicy = remember {
        GeoVaultMapPaddingPolicy(
            includeDefaultFabColumnPadding = true,
            mapPaddingDp = GeoVaultMapPaddingDp(),
        )
    }
    val preloadPaddingPx = remember(density, preloadPaddingPolicy) {
        preloadPaddingPolicy.computeViewportPaddingPx(density)
    }
    val preloadBoundsFitPaddingPx = remember(density, preloadPaddingPolicy) {
        preloadPaddingPolicy.computeBoundsFitPaddingPx(density)
    }
    var lastAppliedTarget by remember(mainMapKey) {
        mutableStateOf<GeoVaultMainMapPreloadCameraTarget?>(null)
    }

    LaunchedEffect(phase, cameraTarget) {
        if (phase != GeoVaultMapPhase.Ready) return@LaunchedEffect
        if (lastAppliedTarget == cameraTarget) {
            return@LaunchedEffect
        }
        when (cameraTarget) {
            is GeoVaultMainMapPreloadCameraTarget.Single -> {
                val target = latLngOrNull(cameraTarget.lat, cameraTarget.lon)
                if (target != null) {
                    map.moveCameraWithPadding(
                        CameraUpdateFactory.newLatLngZoom(
                            target,
                            MapLibreManager.DEFAULT_POINT_ZOOM,
                        ),
                        padding = preloadPaddingPx,
                    )
                }
            }

            is GeoVaultMainMapPreloadCameraTarget.Bounds -> {
                map.moveCameraWithPadding(
                    CameraUpdateFactory.newLatLngBounds(
                        cameraTarget.bounds,
                        preloadBoundsFitPaddingPx[0],
                        preloadBoundsFitPaddingPx[1],
                        preloadBoundsFitPaddingPx[2],
                        preloadBoundsFitPaddingPx[3],
                    ),
                    padding = preloadPaddingPx,
                    maxZoom = MapLibreManager.BOUNDS_FIT_MAX_ZOOM,
                )
            }

            GeoVaultMainMapPreloadCameraTarget.None -> Unit
        }
        lastAppliedTarget = cameraTarget
    }

    if (surfaceMapInHost) {
        GeoVaultMainMapView(
            modifier = modifier,
            map = map,
            showDefaultSourceToggle = false,
            includeDefaultFabColumnPadding = true,
        )
    }
}
