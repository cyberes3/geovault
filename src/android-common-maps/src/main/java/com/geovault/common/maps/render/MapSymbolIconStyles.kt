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
)

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
}
