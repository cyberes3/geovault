package com.geovault.common.maps.render

import androidx.compose.ui.graphics.toArgb
import com.geovault.common.ui.theme.GeoVaultColorTokens
import org.junit.Assert.assertEquals
import org.junit.Test

class CommonMapSymbolIconStylesTest {

    @Test
    fun defaultMarker_usesMainBlueWithLightFrame() {
        val style = CommonMapMarkerStyles.default()
        val frame = CommonMapMarkerStyles.frame(MapMarkerBorderStyle.LIGHT)

        assertEquals(GeoVaultColorTokens.MainBlue.toArgb(), style.centerColorInt)
        assertEquals(frame.outerBorderColorInt, style.outerBorderColorInt)
        assertEquals(frame.innerBorderColorInt, style.innerBorderColorInt)
    }

    @Test
    fun selectedMarker_usesMainYellowWithDarkFrame() {
        val style = CommonMapMarkerStyles.selected()
        val frame = CommonMapMarkerStyles.frame(MapMarkerBorderStyle.DARK)

        assertEquals(GeoVaultColorTokens.MainYellow.toArgb(), style.centerColorInt)
        assertEquals(frame.outerBorderColorInt, style.outerBorderColorInt)
        assertEquals(frame.innerBorderColorInt, style.innerBorderColorInt)
    }

    @Test
    fun navTargetMarker_usesMainPurpleWithLightFrame() {
        val style = CommonMapMarkerStyles.navTarget()
        val frame = CommonMapMarkerStyles.frame(MapMarkerBorderStyle.LIGHT)

        assertEquals(GeoVaultColorTokens.MainPurple.toArgb(), style.centerColorInt)
        assertEquals(frame.outerBorderColorInt, style.outerBorderColorInt)
        assertEquals(frame.innerBorderColorInt, style.innerBorderColorInt)
    }

    @Test
    fun generatedPointMarkerId_includesFillAndFrame() {
        assertEquals(
            "gv-common-marker-dark-ff3e41",
            CommonMapPointIcons.iconImageId("#FF3E41", MapMarkerBorderStyle.DARK),
        )
    }

    @Test
    fun styleOrNull_roundTripsGeneratedId() {
        val imageId = CommonMapPointIcons.iconImageId("#FF3E41", MapMarkerBorderStyle.LIGHT)
        val style = CommonMapPointIcons.styleOrNull(imageId)
        assertEquals(GeoVaultColorTokens.MainRed.toArgb(), style!!.centerColorInt)
        assertEquals(
            CommonMapMarkerStyles.frame(MapMarkerBorderStyle.LIGHT).outerBorderColorInt,
            style.outerBorderColorInt,
        )
    }

    @Test
    fun styleOrNull_rejectsBuiltInDefaultId() {
        assertEquals(null, CommonMapPointIcons.styleOrNull(CommonMapIconIds.MARKER_DEFAULT))
        assertEquals(null, CommonMapPointIcons.styleOrNull("not-a-marker"))
    }

    @Test
    fun generatedPointMarkerStyles_registersEveryFillAndFrameCombination() {
        val styles = CommonMapPointIcons.styles(
            centerColorsByHex = mapOf(
                "#FFFFFF" to GeoVaultColorTokens.White.toArgb(),
                "#FF3E41" to GeoVaultColorTokens.MainRed.toArgb(),
            ),
            borderStyles = listOf(MapMarkerBorderStyle.LIGHT, MapMarkerBorderStyle.DARK),
        )

        assertEquals(4, styles.size)
        assertEquals(
            GeoVaultColorTokens.White.toArgb(),
            styles.getValue("gv-common-marker-light-ffffff").centerColorInt,
        )
        assertEquals(
            CommonMapMarkerStyles.frame(MapMarkerBorderStyle.DARK).outerBorderColorInt,
            styles.getValue("gv-common-marker-dark-ff3e41").outerBorderColorInt,
        )
    }

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
