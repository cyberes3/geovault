package com.geovault.common.maps.core

import com.geovault.common.geo.CoordinateFormat

internal fun formatMapLongPressCoordinates(latitude: Double, longitude: Double): String? {
    if (!isValidMapLibreGeographicLatLng(latitude, longitude)) return null
    return CoordinateFormat.DECIMAL_4.formatLatLon(latitude, longitude)
}
