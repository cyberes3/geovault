package com.geovault.common.maps.core

import kotlin.math.max
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
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

fun geoVaultRetargetCameraPositionPreserveViewport(
    current: CameraPosition,
    target: LatLng,
): CameraPosition =
    CameraPosition.Builder(current)
        .target(target)
        .build()

fun geoVaultRetargetCameraPositionWithMinimumZoom(
    current: CameraPosition,
    target: LatLng,
    minimumZoom: Double,
): CameraPosition =
    CameraPosition.Builder(current)
        .target(target)
        .zoom(max(current.zoom, minimumZoom))
        .build()

fun geoVaultCenterCameraPreserveZoom(map: GeoVaultBaseMap, latitude: Double, longitude: Double) {
    val target = latLngOrNull(latitude, longitude) ?: return
    val mapLibreMap = map.maplibreMap ?: return
    mapLibreMap.setCameraPosition(
        geoVaultRetargetCameraPositionPreserveViewport(
            current = mapLibreMap.cameraPosition,
            target = target,
        )
    )
}
