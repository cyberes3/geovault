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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds

sealed interface GeoVaultMainMapPreloadCameraTarget {
    data object None : GeoVaultMainMapPreloadCameraTarget
    data class Single(val lat: Double, val lon: Double) : GeoVaultMainMapPreloadCameraTarget
    data class Bounds(val bounds: LatLngBounds) : GeoVaultMainMapPreloadCameraTarget
}

class GeoVaultMainMapHandle internal constructor(
    val key: String,
    private val ref: MainMapControllerRef,
) {
    val controller: GeoVaultMapController
        get() = ref.controller

    internal fun release() {
        GeoVaultMainMapControllerStore.release(key)
    }
}

internal interface MainMapControllerRef {
    val controller: GeoVaultMapController
    fun preload()
    fun destroy()
}

private class DefaultMainMapControllerRef(
    context: Context,
) : MainMapControllerRef {
    override val controller: GeoVaultMapController = GeoVaultMapController(context.applicationContext)

    override fun preload() {
        controller.preloadMainMap()
    }

    override fun destroy() {
        controller.onDestroy()
    }
}

object GeoVaultMainMapControllerStore {
    private val lock = Any()
    private data class Entry(
        val ref: MainMapControllerRef,
        var refCount: Int,
    )

    private val controllers = linkedMapOf<String, Entry>()

    fun acquire(context: Context, key: String): GeoVaultMainMapHandle {
        synchronized(lock) {
            val entry = controllers.getOrPut(key) {
                Entry(
                    ref = DefaultMainMapControllerRef(context.applicationContext),
                    refCount = 0,
                )
            }
            entry.refCount += 1
            return GeoVaultMainMapHandle(key = key, ref = entry.ref)
        }
    }

    fun release(key: String) {
        var refToDestroy: MainMapControllerRef? = null
        synchronized(lock) {
            val entry = controllers[key] ?: return
            if (entry.refCount > 1) {
                entry.refCount -= 1
            } else {
                refToDestroy = controllers.remove(key)?.ref
            }
        }
        refToDestroy?.destroy()
    }

    /**
     * Deterministic teardown for reset/auth-flush flows.
     * This removes and destroys the key regardless of current ref-count.
     */
    fun forceReleaseKeyForReset(key: String) {
        val refToDestroy = synchronized(lock) {
            controllers.remove(key)?.ref
        }
        refToDestroy?.destroy()
    }

    fun releaseAll() {
        val refsToDestroy = mutableListOf<MainMapControllerRef>()
        synchronized(lock) {
            refsToDestroy.addAll(controllers.values.map { it.ref })
            controllers.clear()
        }
        refsToDestroy.forEach { ref ->
            ref.destroy()
        }
    }

    fun preload(context: Context, key: String) {
        val ref = synchronized(lock) {
            val entry = controllers.getOrPut(key) {
                Entry(
                    ref = DefaultMainMapControllerRef(context.applicationContext),
                    refCount = 0,
                )
            }
            entry.ref
        }
        ref.preload()
    }

    @VisibleForTesting
    internal fun currentRefCountForTest(key: String): Int {
        synchronized(lock) {
            return controllers[key]?.refCount ?: 0
        }
    }

    @VisibleForTesting
    internal fun acquireForTest(
        key: String,
        factory: () -> MainMapControllerRef,
    ): GeoVaultMainMapHandle {
        synchronized(lock) {
            val entry = controllers.getOrPut(key) {
                Entry(
                    ref = factory(),
                    refCount = 0,
                )
            }
            entry.refCount += 1
            return GeoVaultMainMapHandle(key = key, ref = entry.ref)
        }
    }

    @VisibleForTesting
    internal fun preloadForTest(key: String) {
        val ref = synchronized(lock) {
            controllers[key]?.ref
        }
        ref?.preload()
    }

    @VisibleForTesting
    internal fun resetForTest() {
        releaseAll()
    }
}

@Composable
fun rememberGeoVaultMainMapHandle(
    key: String,
): GeoVaultMainMapHandle {
    val context = androidx.compose.ui.platform.LocalContext.current
    val handle = remember(context, key) {
        GeoVaultMainMapControllerStore.acquire(context.applicationContext, key)
    }
    DisposableEffect(handle) {
        onDispose {
            handle.release()
        }
    }
    return handle
}

@Composable
fun rememberGeoVaultMainMapController(
    key: String,
): GeoVaultMapController {
    val handle = rememberGeoVaultMainMapHandle(key)
    return handle.controller
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
    if (!enabled) {
        return
    }

    val handle = rememberGeoVaultMainMapHandle(mainMapKey)
    val controller = handle.controller
    val phase by controller.phase.collectAsState()
    var lastAppliedTarget by remember(mainMapKey) {
        mutableStateOf<GeoVaultMainMapPreloadCameraTarget?>(null)
    }

    LaunchedEffect(phase, cameraTarget, enabled) {
        if (!enabled) return@LaunchedEffect
        if (phase != GeoVaultMapPhase.Ready) return@LaunchedEffect
        if (lastAppliedTarget == cameraTarget) {
            return@LaunchedEffect
        }
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
        lastAppliedTarget = cameraTarget
    }

    GeoVaultMap(
        modifier = modifier,
        controller = controller,
        showDefaultSourceToggle = false,
        mapMode = GeoVaultMapMode.Main,
    )
}
