package com.geovault.common.maps.render

import androidx.annotation.ColorInt
import androidx.compose.ui.graphics.toArgb
import com.geovault.common.ui.theme.GeoVaultColorTokens

data class MapMarkerStyle(
    @param:ColorInt val outerBorderColorInt: Int,
    @param:ColorInt val innerBorderColorInt: Int,
    @param:ColorInt val centerColorInt: Int,
)

data class MapMarkerFrameStyle(
    @param:ColorInt val outerBorderColorInt: Int,
    @param:ColorInt val innerBorderColorInt: Int,
) {
    fun withCenterColor(@ColorInt centerColorInt: Int): MapMarkerStyle = MapMarkerStyle(
        outerBorderColorInt = outerBorderColorInt,
        innerBorderColorInt = innerBorderColorInt,
        centerColorInt = centerColorInt,
    )
}

enum class MapMarkerBorderStyle {
    LIGHT,
    DARK,
}

object CommonMapPointIcons {
    private const val ID_PREFIX = "gv-common-marker"

    fun iconImageId(centerColorHex: String, borderStyle: MapMarkerBorderStyle = MapMarkerBorderStyle.LIGHT): String {
        val normalizedColor = centerColorHex.trim()
            .removePrefix("#")
            .lowercase()
            .ifBlank { "unknown" }
        return "$ID_PREFIX-${borderStyle.idSegment}-$normalizedColor"
    }

    fun style(
        @ColorInt centerColorInt: Int,
        borderStyle: MapMarkerBorderStyle = MapMarkerBorderStyle.LIGHT,
    ): MapMarkerStyle = CommonMapMarkerStyles.fromCenterColorInt(centerColorInt, borderStyle)

    fun styles(
        centerColorsByHex: Map<String, Int>,
        borderStyles: Iterable<MapMarkerBorderStyle> = listOf(MapMarkerBorderStyle.LIGHT),
    ): Map<String, MapMarkerStyle> {
        return buildMap {
            centerColorsByHex.forEach { (hex, colorInt) ->
                borderStyles.forEach { borderStyle ->
                    put(iconImageId(hex, borderStyle), style(colorInt, borderStyle))
                }
            }
        }
    }
}

object CommonMapMarkerStyles {
    fun frame(borderStyle: MapMarkerBorderStyle): MapMarkerFrameStyle {
        val (outerBorderColorInt, innerBorderColorInt) = when (borderStyle) {
            MapMarkerBorderStyle.LIGHT ->
                GeoVaultColorTokens.Black.toArgb() to GeoVaultColorTokens.Surface.toArgb()
            MapMarkerBorderStyle.DARK ->
                GeoVaultColorTokens.Surface.toArgb() to GeoVaultColorTokens.Black.toArgb()
        }
        return MapMarkerFrameStyle(
            outerBorderColorInt = outerBorderColorInt,
            innerBorderColorInt = innerBorderColorInt,
        )
    }

    fun fromCenterColorHex(centerColorHex: String, borderStyle: MapMarkerBorderStyle): MapMarkerStyle {
        return fromCenterColorInt(parseColorHex(centerColorHex), borderStyle)
    }

    fun fromCenterColorInt(@ColorInt centerColorInt: Int, borderStyle: MapMarkerBorderStyle): MapMarkerStyle =
        frame(borderStyle).withCenterColor(centerColorInt)

    fun default(): MapMarkerStyle = fromCenterColorInt(
        centerColorInt = GeoVaultColorTokens.MainBlue.toArgb(),
        borderStyle = MapMarkerBorderStyle.LIGHT,
    )

    fun selected(): MapMarkerStyle = fromCenterColorInt(
        centerColorInt = GeoVaultColorTokens.MainYellow.toArgb(),
        borderStyle = MapMarkerBorderStyle.DARK,
    )

    fun navTarget(): MapMarkerStyle = fromCenterColorInt(
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

private val MapMarkerBorderStyle.idSegment: String
    get() = when (this) {
        MapMarkerBorderStyle.LIGHT -> "light"
        MapMarkerBorderStyle.DARK -> "dark"
    }
