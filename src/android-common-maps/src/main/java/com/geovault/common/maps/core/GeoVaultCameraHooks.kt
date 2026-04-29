package com.geovault.common.maps.core

import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.maps.MapLibreMap

fun geoVaultCreateGestureMoveStartedListener(onGestureMoveStarted: () -> Unit): MapLibreMap.OnCameraMoveStartedListener {
    return MapLibreMap.OnCameraMoveStartedListener { reason ->
        if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
            onGestureMoveStarted()
        }
    }
}

fun geoVaultResetCameraBearingAndTilt(map: GeoVaultBaseMap) {
    val mapLibreMap = map.maplibreMap ?: return
    mapLibreMap.setCameraPosition(
        CameraPosition.Builder(mapLibreMap.cameraPosition)
            .bearing(0.0)
            .tilt(0.0)
            .build()
    )
}

fun geoVaultCenterCameraPreserveZoom(map: GeoVaultBaseMap, latitude: Double, longitude: Double) {
    val target = latLngOrNull(latitude, longitude) ?: return
    val mapLibreMap = map.maplibreMap ?: return
    mapLibreMap.setCameraPosition(
        CameraPosition.Builder(mapLibreMap.cameraPosition)
            .target(target)
            .build()
    )
}
