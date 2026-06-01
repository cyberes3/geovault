package com.geovault.common.geo

object GeoCoordinates {
    fun isValidGeographic(latitude: Double, longitude: Double): Boolean {
        if (!latitude.isFinite() || !longitude.isFinite()) return false
        return latitude in -90.0..90.0 && longitude in -180.0..180.0
    }

    fun isValidGeographic(latitude: Float, longitude: Float): Boolean {
        return isValidGeographic(latitude.toDouble(), longitude.toDouble())
    }
}
