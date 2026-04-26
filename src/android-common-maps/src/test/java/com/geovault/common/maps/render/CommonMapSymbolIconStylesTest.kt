package com.geovault.common.maps.render

import androidx.compose.ui.graphics.toArgb
import com.geovault.common.ui.theme.GeoVaultColorTokens
import org.junit.Assert.assertEquals
import org.junit.Test

class CommonMapSymbolIconStylesTest {

    @Test
    fun station_usesMainGreen() {
        assertEquals(
            GeoVaultColorTokens.MainGreen.toArgb(),
            CommonMapSymbolIconStyles.station().backgroundTintColorInt,
        )
    }

    @Test
    fun selectedStation_usesMainYellow() {
        assertEquals(
            GeoVaultColorTokens.MainYellow.toArgb(),
            CommonMapSymbolIconStyles.selectedStation().backgroundTintColorInt,
        )
    }

    @Test
    fun stationNavTarget_usesMainPurple() {
        assertEquals(
            GeoVaultColorTokens.MainPurple.toArgb(),
            CommonMapSymbolIconStyles.stationNavTarget().backgroundTintColorInt,
        )
    }
}
