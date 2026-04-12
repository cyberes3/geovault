package com.geovault.common.maps.render

import org.junit.Assert.assertEquals
import org.junit.Test

class CommonMapMarkerStylesTest {
    @Test
    fun fromCenterColorInt_lightBorders_useBlackOuterWhiteInner() {
        val style = CommonMapMarkerStyles.fromCenterColorInt(
            centerColorInt = 0xFF123456.toInt(),
            borderStyle = MapMarkerBorderStyle.LIGHT,
        )

        assertEquals(0xFF000000.toInt(), style.outerBorderColorInt)
        assertEquals(0xFFFFFFFF.toInt(), style.innerBorderColorInt)
        assertEquals(0xFF123456.toInt(), style.centerColorInt)
    }

    @Test
    fun fromCenterColorInt_darkBorders_useWhiteOuterBlackInner() {
        val style = CommonMapMarkerStyles.fromCenterColorInt(
            centerColorInt = 0xFFABCDEF.toInt(),
            borderStyle = MapMarkerBorderStyle.DARK,
        )

        assertEquals(0xFFFFFFFF.toInt(), style.outerBorderColorInt)
        assertEquals(0xFF000000.toInt(), style.innerBorderColorInt)
        assertEquals(0xFFABCDEF.toInt(), style.centerColorInt)
    }

    @Test
    fun fromCenterColorHex_supportsRgbHex() {
        val style = CommonMapMarkerStyles.fromCenterColorHex(
            centerColorHex = "#163D8A",
            borderStyle = MapMarkerBorderStyle.LIGHT,
        )

        assertEquals(0xFF163D8A.toInt(), style.centerColorInt)
    }

    @Test
    fun fromCenterColorHex_supportsArgbHex() {
        val style = CommonMapMarkerStyles.fromCenterColorHex(
            centerColorHex = "#80163D8A",
            borderStyle = MapMarkerBorderStyle.DARK,
        )

        assertEquals(0x80163D8A.toInt(), style.centerColorInt)
    }
}
