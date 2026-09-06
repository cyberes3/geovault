package com.geovault.common.geo

import java.util.Locale

/**
 * Shared decimal-degree precisions so map long-press, the parser, and URL builders
 * do not drift apart.
 */
enum class CoordinateFormat(val decimalPlaces: Int) {
    DECIMAL_4(4),
    DECIMAL_6(6),
    DECIMAL_8(8),
    ;

    fun format(value: Double): String =
        String.format(Locale.US, "%.${decimalPlaces}f", value)

    fun formatLatLon(latitude: Double, longitude: Double): String =
        "${format(latitude)}, ${format(longitude)}"

    fun formatLatLon(point: Wgs84Point): String =
        formatLatLon(point.latitude, point.longitude)

    /** Comma-joined lat,lon with no space, used in maps `q=` query values. */
    fun formatLatLonCompact(latitude: Double, longitude: Double): String =
        "${format(latitude)},${format(longitude)}"
}
