package com.geovault.common.maps.render

import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.toArgb
import com.geovault.common.maps.R
import com.geovault.common.ui.theme.GeoVaultColorTokens

data class MapSymbolIconStyle(
    @param:DrawableRes val backgroundDrawableResId: Int,
    @param:ColorInt val backgroundTintColorInt: Int,
)

object CommonMapSymbolIconStyles {
    fun station(): MapSymbolIconStyle = MapSymbolIconStyle(
        backgroundDrawableResId = R.drawable.gv_common_ic_station_point,
        backgroundTintColorInt = GeoVaultColorTokens.MainGreen.toArgb(),
    )

    fun selectedStation(): MapSymbolIconStyle = MapSymbolIconStyle(
        backgroundDrawableResId = R.drawable.gv_common_ic_station_point,
        backgroundTintColorInt = GeoVaultColorTokens.MainYellow.toArgb(),
    )
}
