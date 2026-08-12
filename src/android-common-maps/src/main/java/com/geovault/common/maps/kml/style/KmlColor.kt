package com.geovault.common.maps.kml.style

/**
 * Port of backend `geo_lib.togeojson.kml.fix_color.fix_color`: KML `aabbggrr`
 * (and shortest `#rgb` / `#rrggbb`) into simplestyle `#rrggbb` plus opacity.
 */
data class KmlColor(
    val hexRgb: String,
    val opacity: Double? = null,
)

object KmlColors {

    fun parse(value: String?): KmlColor? {
        if (value.isNullOrBlank()) return null
        var v = value.trim()
        if (v.startsWith("#")) {
            v = v.substring(1)
        }
        return when (v.length) {
            3, 6 -> KmlColor(hexRgb = "#$v")
            8 -> {
                val opacity = v.substring(0, 2).toIntOrNull(16)?.div(255.0)
                val hexRgb = "#${v.substring(6, 8)}${v.substring(4, 6)}${v.substring(2, 4)}"
                KmlColor(hexRgb = hexRgb, opacity = opacity)
            }
            else -> null
        }
    }
}
