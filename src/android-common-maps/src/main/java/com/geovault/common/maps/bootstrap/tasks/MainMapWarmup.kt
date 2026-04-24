package com.geovault.common.maps.bootstrap.tasks

import android.content.Context
import com.geovault.common.bootstrap.BackgroundTask
import com.geovault.common.maps.core.preloadGeoVaultMainMapOnAppLaunch

/**
 * Pre-warm the shared MapLibre [com.geovault.common.maps.core.GeoVaultMainMapControllerStore]
 * entry under [key] so that switching to the Map tab is instantaneous on first use.
 *
 * Runs as a [BackgroundTask] — `preloadGeoVaultMainMapOnAppLaunch` posts onto the main
 * looper and spins up a retained `MapView`. We never block the first frame on map preload.
 */
class MainMapWarmup(
    private val key: String,
) : BackgroundTask(ID) {

    override suspend fun execute(context: Context) {
        preloadGeoVaultMainMapOnAppLaunch(context, key)
    }

    companion object {
        const val ID = "main-map"
    }
}
