package com.geovault.common.maps.core

import org.maplibre.android.geometry.LatLng

/**
 * [org.maplibre.android.geometry.LatLng] rejects non-finite values and out-of-range latitude/longitude.
 * Use this before building [LatLng] (or [org.maplibre.android.geometry.LatLngBounds]) from untrusted
 * network or queue data (e.g. lon/lat swap, corrupt fixes).
 */
fun isValidMapLibreGeographicLatLng(latitude: Double, longitude: Double): Boolean {
    if (!latitude.isFinite() || !longitude.isFinite()) return false
    return latitude in -90.0..90.0 && longitude in -180.0..180.0
}

/** [LatLng] from doubles only when [isValidMapLibreGeographicLatLng] holds; otherwise null. */
fun latLngOrNull(latitude: Double, longitude: Double): LatLng? {
    if (!isValidMapLibreGeographicLatLng(latitude, longitude)) return null
    return LatLng(latitude, longitude)
}
