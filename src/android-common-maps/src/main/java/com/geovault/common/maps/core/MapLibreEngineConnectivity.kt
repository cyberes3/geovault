package com.geovault.common.maps.core

import android.content.Context
import android.util.Log
import org.maplibre.android.net.ConnectivityReceiver

internal enum class MapLibreConnectivityMode {
    FollowSystem,
    CacheOnly,
}

internal object MapLibreEngineConnectivity {
    private const val TAG = "MapLibreEngineNet"

    @Volatile
    private var currentMode: MapLibreConnectivityMode = MapLibreConnectivityMode.FollowSystem

    fun apply(context: Context, mode: MapLibreConnectivityMode) {
        currentMode = mode
        val receiver = ConnectivityReceiver.instance(context.applicationContext)
        when (mode) {
            MapLibreConnectivityMode.CacheOnly -> {
                receiver.setConnected(false)
                Log.i(TAG, "MapLibre connectivity forced cache-only")
            }
            MapLibreConnectivityMode.FollowSystem -> {
                receiver.setConnected(null)
                Log.i(TAG, "MapLibre connectivity follows system")
            }
        }
    }

    fun currentMode(): MapLibreConnectivityMode = currentMode
}
