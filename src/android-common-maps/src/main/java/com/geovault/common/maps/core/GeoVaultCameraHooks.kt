package com.geovault.common.maps.core

import kotlin.math.max
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng

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

/**
 * Centers the camera on [latitude]/[longitude], zooming in to [minimumZoom] if the current zoom
 * is wider than that floor, but never zooming back out if the user (or a prior camera move) has
 * already zoomed in further. Mirrors the same "focus the position, don't fight a closer zoom"
 * semantics already used for GPS follow/recenter
 * ([geoVaultRetargetCameraPositionWithMinimumZoom]'s other callers); use this instead of
 * [geoVaultCenterCameraPreserveZoom] whenever a lock/focus engaging for the first time should
 * actually bring the camera in on the point rather than leave it at whatever zoom happened to be
 * on screen.
 */
fun geoVaultCenterCameraWithMinimumZoom(
    map: GeoVaultBaseMap,
    latitude: Double,
    longitude: Double,
    minimumZoom: Double,
) {
    val target = latLngOrNull(latitude, longitude) ?: return
    val mapLibreMap = map.maplibreMap ?: return
    mapLibreMap.setCameraPosition(
        geoVaultRetargetCameraPositionWithMinimumZoom(
            current = mapLibreMap.cameraPosition,
            target = target,
            minimumZoom = minimumZoom,
        )
    )
}
