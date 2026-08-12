package com.geovault.common.maps.kml.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KmlColorsTest {

    @Test
    fun eightDigitAabbggrr_swapsToRrggbbAndOpacity() {
        val color = KmlColors.parse("ff2dc0fb")
        assertEquals("#fbc02d", color!!.hexRgb)
        assertEquals(1.0, color.opacity!!, 0.0)
    }

    @Test
    fun eightDigit_withHashPrefix() {
        val color = KmlColors.parse("#8000ff00")
        assertEquals("#00ff00", color!!.hexRgb)
        assertEquals(128.0 / 255.0, color.opacity!!, 1e-9)
    }

    @Test
    fun sixDigit_isRrggbbNotAabbggrr() {
        val color = KmlColors.parse("ff0000")
        assertEquals("#ff0000", color!!.hexRgb)
        assertNull(color.opacity)
    }

    @Test
    fun threeDigit_keptAsShortHex() {
        val color = KmlColors.parse("#abc")
        assertEquals("#abc", color!!.hexRgb)
        assertNull(color.opacity)
    }

    @Test
    fun blank_isNull() {
        assertNull(KmlColors.parse(null))
        assertNull(KmlColors.parse("  "))
        assertNull(KmlColors.parse("ffff"))
    }
}
