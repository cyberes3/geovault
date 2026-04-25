package com.geovault.common.maps.render

import androidx.annotation.ColorInt
import androidx.compose.ui.graphics.toArgb
import com.geovault.common.ui.theme.GeoVaultColorTokens

data class MapMarkerStyle(
    @param:ColorInt val outerBorderColorInt: Int,
    @param:ColorInt val innerBorderColorInt: Int,
    @param:ColorInt val centerColorInt: Int,
)

enum class MapMarkerBorderStyle {
    LIGHT,
    DARK,
}

object CommonMapMarkerStyles {
    fun fromCenterColorHex(centerColorHex: String, borderStyle: MapMarkerBorderStyle): MapMarkerStyle {
        return fromCenterColorInt(parseColorHex(centerColorHex), borderStyle)
    }

    fun fromCenterColorInt(@ColorInt centerColorInt: Int, borderStyle: MapMarkerBorderStyle): MapMarkerStyle {
        val (outerBorderColorInt, innerBorderColorInt) = when (borderStyle) {
            MapMarkerBorderStyle.LIGHT ->
                GeoVaultColorTokens.Black.toArgb() to GeoVaultColorTokens.Surface.toArgb()
            MapMarkerBorderStyle.DARK ->
                GeoVaultColorTokens.Surface.toArgb() to GeoVaultColorTokens.Black.toArgb()
        }
        return MapMarkerStyle(
            outerBorderColorInt = outerBorderColorInt,
            innerBorderColorInt = innerBorderColorInt,
            centerColorInt = centerColorInt,
        )
    }

    fun default(): MapMarkerStyle = fromCenterColorInt(
        centerColorInt = GeoVaultColorTokens.MainBlue.toArgb(),
        borderStyle = MapMarkerBorderStyle.LIGHT,
    )

    fun selected(): MapMarkerStyle = fromCenterColorInt(
        centerColorInt = GeoVaultColorTokens.MainYellow.toArgb(),
        borderStyle = MapMarkerBorderStyle.DARK,
    )

    /**
     * Same concentric structure as [default] (the standard blue map point), with a purple
     * center — used for the “navigate to point” target on the map.
     */
    fun navigationToPointTarget(): MapMarkerStyle = fromCenterColorInt(
        centerColorInt = GeoVaultColorTokens.MainPurple.toArgb(),
        borderStyle = MapMarkerBorderStyle.LIGHT,
    )

    private fun parseColorHex(value: String): Int {
        val normalized = value.removePrefix("#")
        return when (normalized.length) {
            6 -> (0xFF000000L or normalized.toLong(16)).toInt()
            8 -> normalized.toLong(16).toInt()
            else -> throw IllegalArgumentException("Expected hex color in #RRGGBB or #AARRGGBB format.")
        }
    }
}
