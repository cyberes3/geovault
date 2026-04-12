package com.geovault.common.maps.core

import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.geometry.LatLng

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
    val mapLibreMap = map.maplibreMap ?: return
    mapLibreMap.setCameraPosition(
        CameraPosition.Builder(mapLibreMap.cameraPosition)
            .target(LatLng(latitude, longitude))
            .build()
    )
}
