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

    @Test
    fun generatedStationMarkerId_includesFillAndSymbol() {
        assertEquals(
            "gv-common-station-marker-ff3e41-disk",
            CommonMapStationMarkerIcons.iconImageId("#FF3E41", StationMarkerSymbol.Disk),
        )
    }

    @Test
    fun generatedStationMarkerStyle_usesFillAndOptionalSymbol() {
        val style = CommonMapStationMarkerIcons.style(
            fillColorInt = GeoVaultColorTokens.MainRed.toArgb(),
            symbol = StationMarkerSymbol.Intersection,
        )

        assertEquals(GeoVaultColorTokens.MainRed.toArgb(), style.backgroundTintColorInt)
        assertEquals(StationMarkerSymbol.Intersection, style.stationMarkerSymbol)
    }

    @Test
    fun generatedStationMarkerStyles_registersEveryFillAndSymbolCombination() {
        val styles = CommonMapStationMarkerIcons.styles(
            fillColorsByHex = mapOf(
                "#FFFFFF" to GeoVaultColorTokens.White.toArgb(),
                "#FF3E41" to GeoVaultColorTokens.MainRed.toArgb(),
            ),
            symbols = listOf(null, StationMarkerSymbol.Disk),
        )

        assertEquals(4, styles.size)
        assertEquals(
            GeoVaultColorTokens.White.toArgb(),
            styles.getValue("gv-common-station-marker-ffffff").backgroundTintColorInt,
        )
        assertEquals(
            StationMarkerSymbol.Disk,
            styles.getValue("gv-common-station-marker-ff3e41-disk").stationMarkerSymbol,
        )
    }
}
