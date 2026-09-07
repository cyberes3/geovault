package com.geovault.common.maps.core

import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds

fun GeoVaultBaseMap.animateCameraToHomeFit(
    bounds: LatLngBounds?,
    gpsAnchor: LatLng?,
    paddingPx: IntArray,
) {
    geoVaultResetCameraBearingAndTilt(this)
    val effectiveBounds = when {
        bounds != null && gpsAnchor != null -> geoVaultLatLngBoundsUnion(bounds, listOf(gpsAnchor))
        else -> bounds
    } ?: return
    animateCameraToFitLatLngBounds(effectiveBounds, paddingPx)
}
