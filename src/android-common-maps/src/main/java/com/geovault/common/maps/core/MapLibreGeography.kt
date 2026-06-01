package com.geovault.common.maps.core

import com.geovault.common.geo.GeoCoordinates
import org.maplibre.android.geometry.LatLng

/**
 * [org.maplibre.android.geometry.LatLng] rejects non-finite values and out-of-range latitude/longitude.
 * Use this before building [LatLng] (or [org.maplibre.android.geometry.LatLngBounds]) from untrusted
 * network or queue data (e.g. lon/lat swap, corrupt fixes).
 */
fun isValidMapLibreGeographicLatLng(latitude: Double, longitude: Double): Boolean =
    GeoCoordinates.isValidGeographic(latitude, longitude)

/** [LatLng] from doubles only when [isValidMapLibreGeographicLatLng] holds; otherwise null. */
fun latLngOrNull(latitude: Double, longitude: Double): LatLng? {
    if (!isValidMapLibreGeographicLatLng(latitude, longitude)) return null
    return LatLng(latitude, longitude)
}
