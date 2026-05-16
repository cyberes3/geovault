package com.geovault.common.maps.core

import java.util.Locale

internal fun formatMapLongPressCoordinates(latitude: Double, longitude: Double): String? {
    if (!isValidMapLibreGeographicLatLng(latitude, longitude)) return null
    return String.format(Locale.US, "%.4f, %.4f", latitude, longitude)
}
