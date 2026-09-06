package com.geovault.common.maps.bootstrap

import android.app.Application
import com.geovault.common.bootstrap.AppResetFlow
import com.geovault.common.bootstrap.BootstrapExtension
import com.geovault.common.bootstrap.GeoVaultAppBootstrap
import com.geovault.common.maps.bootstrap.tasks.MainMapWarmup
import com.geovault.common.maps.core.GeoVaultMainMapControllerStore
import com.geovault.common.maps.core.MapLibreInitializer

/**
 * [BootstrapExtension] that wires the MapLibre subsystem into a [GeoVaultAppBootstrap].
 *
 * Apps that show maps simply add `.install(GeoVaultMapsBootstrap(MAP_KEY))` to their
 * builder chain in `Application.onCreate`. The extension:
 *
 *  - Calls [MapLibreInitializer.init] synchronously on the main thread (required —
 *    MapLibre 12.x registers a main-thread-bound connectivity receiver and installs
 *    handlers on the calling thread during `getInstance()`). The initializer is
 *    idempotent and `@Synchronized`, so repeat calls are safe no-ops.
 *  - Registers a [MainMapWarmup] background task that pre-warms the shared
 *    [GeoVaultMainMapControllerStore] entry under [mainMapKey].
 *  - Registers an [AppResetFlow.Phase.AFTER_TOKEN_CLEAR] hook that releases that
 *    same map controller during a reset — otherwise the retained `MapView` would
 *    leak across the relaunch.
 *
 * @param mainMapKey The well-known key the app uses for its primary map.
 */
class GeoVaultMapsBootstrap(
    private val mainMapKey: String,
    private val prewarmMainMap: Boolean = true,
) : BootstrapExtension {

    override fun configure(builder: GeoVaultAppBootstrap.Builder, application: Application) {
        MapLibreInitializer.init(application)

        if (prewarmMainMap) {
            builder.background(MainMapWarmup(mainMapKey))
        }

        builder.resetHook(
            key = HOOK_RELEASE_MAIN_MAP,
            phase = AppResetFlow.Phase.AFTER_TOKEN_CLEAR,
        ) {
            GeoVaultMainMapControllerStore.releaseKey(mainMapKey)
        }
    }

    companion object {
        private const val HOOK_RELEASE_MAIN_MAP = "geovault_maps_release_main_map"
    }
}
