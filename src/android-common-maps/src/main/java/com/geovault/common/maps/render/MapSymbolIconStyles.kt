package com.geovault.common.maps.render

import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.toArgb
import com.geovault.common.maps.R
import com.geovault.common.ui.theme.GeoVaultColorTokens

data class MapSymbolIconStyle(
    @param:DrawableRes val backgroundDrawableResId: Int,
    @param:ColorInt val backgroundTintColorInt: Int,
    /**
     * When non-null, this drawable is drawn on top of the tinted background at the same bounds
     * (e.g. a vector stroke) without tinting—use for a fixed-color outline around a tinted fill.
     */
    @param:DrawableRes val overlayStrokeDrawableResId: Int? = null,
    val stationMarkerSymbol: StationMarkerSymbol? = null,
    @param:ColorInt val stationMarkerSymbolColorInt: Int = GeoVaultColorTokens.Black.toArgb(),
    @param:ColorInt val stationMarkerSymbolHaloColorInt: Int = GeoVaultColorTokens.White.toArgb(),
)

enum class StationMarkerSymbol(val idSegment: String) {
    Plus("plus"),
    Minus("minus"),
    Pipe("pipe"),
    Disk("disk"),
    Intersection("int"),
}

object CommonMapStationMarkerIcons {
    private const val ID_PREFIX = "gv-common-station-marker"

    fun iconImageId(fillColorHex: String, symbol: StationMarkerSymbol? = null): String {
        val normalizedColor = fillColorHex.trim()
            .removePrefix("#")
            .lowercase()
            .ifBlank { "unknown" }
        return buildString {
            append(ID_PREFIX)
            append('-')
            append(normalizedColor)
            symbol?.let {
                append('-')
                append(it.idSegment)
            }
        }
    }

    fun style(
        @ColorInt fillColorInt: Int,
        symbol: StationMarkerSymbol? = null,
    ): MapSymbolIconStyle = MapSymbolIconStyle(
        backgroundDrawableResId = R.drawable.gv_common_ic_station_point,
        backgroundTintColorInt = fillColorInt,
        overlayStrokeDrawableResId = R.drawable.gv_common_ic_station_point_stroke,
        stationMarkerSymbol = symbol,
    )

    fun styles(
        fillColorsByHex: Map<String, Int>,
        symbols: Iterable<StationMarkerSymbol?>,
    ): Map<String, MapSymbolIconStyle> {
        return buildMap {
            fillColorsByHex.forEach { (hex, colorInt) ->
                symbols.forEach { symbol ->
                    put(iconImageId(hex, symbol), style(colorInt, symbol))
                }
            }
        }
    }
}

object CommonMapSymbolIconStyles {
    fun station(): MapSymbolIconStyle = MapSymbolIconStyle(
        backgroundDrawableResId = R.drawable.gv_common_ic_station_point,
        backgroundTintColorInt = GeoVaultColorTokens.MainGreen.toArgb(),
        overlayStrokeDrawableResId = R.drawable.gv_common_ic_station_point_stroke,
    )

    fun selectedStation(): MapSymbolIconStyle = MapSymbolIconStyle(
        backgroundDrawableResId = R.drawable.gv_common_ic_station_point,
        backgroundTintColorInt = GeoVaultColorTokens.MainYellow.toArgb(),
        overlayStrokeDrawableResId = R.drawable.gv_common_ic_station_point_stroke,
    )

    fun stationNavTarget(): MapSymbolIconStyle = MapSymbolIconStyle(
        backgroundDrawableResId = R.drawable.gv_common_ic_station_point,
        backgroundTintColorInt = GeoVaultColorTokens.MainPurple.toArgb(),
        overlayStrokeDrawableResId = R.drawable.gv_common_ic_station_point_stroke,
    )
}
