package com.geovault.common.maps.render

import androidx.compose.ui.graphics.toArgb
import com.geovault.common.ui.theme.GeoVaultColorTokens
import org.junit.Assert.assertEquals
import org.junit.Test

class CommonMapMarkerStylesTest {
    @Test
    fun fromCenterColorInt_lightBorders_useBlackOuterWhiteInner() {
        val style = CommonMapMarkerStyles.fromCenterColorInt(
            centerColorInt = 0xFF123456.toInt(),
            borderStyle = MapMarkerBorderStyle.LIGHT,
        )

        assertEquals(GeoVaultColorTokens.Black.toArgb(), style.outerBorderColorInt)
        assertEquals(GeoVaultColorTokens.Surface.toArgb(), style.innerBorderColorInt)
        assertEquals(0xFF123456.toInt(), style.centerColorInt)
    }

    @Test
    fun fromCenterColorInt_darkBorders_useWhiteOuterBlackInner() {
        val style = CommonMapMarkerStyles.fromCenterColorInt(
            centerColorInt = 0xFFABCDEF.toInt(),
            borderStyle = MapMarkerBorderStyle.DARK,
        )

        assertEquals(GeoVaultColorTokens.Surface.toArgb(), style.outerBorderColorInt)
        assertEquals(GeoVaultColorTokens.Black.toArgb(), style.innerBorderColorInt)
        assertEquals(0xFFABCDEF.toInt(), style.centerColorInt)
    }

    @Test
    fun fromCenterColorHex_supportsRgbHex() {
        val style = CommonMapMarkerStyles.fromCenterColorHex(
            centerColorHex = GeoVaultColorTokens.Hex.MainBlue,
            borderStyle = MapMarkerBorderStyle.LIGHT,
        )

        assertEquals(GeoVaultColorTokens.MainBlue.toArgb(), style.centerColorInt)
    }

    @Test
    fun fromCenterColorHex_supportsArgbHex() {
        val style = CommonMapMarkerStyles.fromCenterColorHex(
            centerColorHex = "#80163D8A",
            borderStyle = MapMarkerBorderStyle.DARK,
        )

        assertEquals(0x80163D8A.toInt(), style.centerColorInt)
    }

    @Test
    fun default_usesMainBlueWithLightBorder() {
        val style = CommonMapMarkerStyles.default()

        assertEquals(GeoVaultColorTokens.MainBlue.toArgb(), style.centerColorInt)
        assertEquals(GeoVaultColorTokens.Black.toArgb(), style.outerBorderColorInt)
        assertEquals(GeoVaultColorTokens.Surface.toArgb(), style.innerBorderColorInt)
    }

    @Test
    fun selected_usesMainYellowWithDarkBorder() {
        val style = CommonMapMarkerStyles.selected()

        assertEquals(GeoVaultColorTokens.MainYellow.toArgb(), style.centerColorInt)
        assertEquals(GeoVaultColorTokens.Surface.toArgb(), style.outerBorderColorInt)
        assertEquals(GeoVaultColorTokens.Black.toArgb(), style.innerBorderColorInt)
    }

    @Test
    fun navigationToPointTarget_usesMainPurpleWithLightBorder() {
        val style = CommonMapMarkerStyles.navigationToPointTarget()
        assertEquals(GeoVaultColorTokens.MainPurple.toArgb(), style.centerColorInt)
        assertEquals(GeoVaultColorTokens.Black.toArgb(), style.outerBorderColorInt)
        assertEquals(GeoVaultColorTokens.Surface.toArgb(), style.innerBorderColorInt)
    }
}
