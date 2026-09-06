package com.geovault.common.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class GeoVaultColorHexTest {

    @Test
    fun normalizeAndValidate() {
        assertEquals("#AA33CC", GeoVaultColorHex.normalizeHashPrefix("AA33CC"))
        assertEquals("#aa33cc", GeoVaultColorHex.normalizeForCompare("AA33CC"))
        assertTrue(GeoVaultColorHex.isValid("#abc"))
        assertTrue(GeoVaultColorHex.isValid("11223344"))
        assertFalse(GeoVaultColorHex.isValid("not-a-color"))
    }

    @Test
    fun parseFormatAndRgba() {
        val fallback = 0xFF3366CC.toInt()
        val parsed = GeoVaultColorHex.parseColorInt("#AA33CC", fallback)
        assertEquals("#AA33CC", GeoVaultColorHex.formatRgb(parsed))
        assertEquals(
            "rgba(170,51,204,0.2509804)",
            GeoVaultColorHex.toRgbaCss("#AA33CC", 0x40, "#000000"),
        )
    }
}
