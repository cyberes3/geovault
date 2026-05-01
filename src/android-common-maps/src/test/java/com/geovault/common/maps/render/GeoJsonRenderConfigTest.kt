package com.geovault.common.maps.render

import com.geovault.common.ui.theme.GeoVaultColorTokens
import org.junit.Assert.assertEquals
import org.junit.Test

class GeoJsonRenderConfigTest {

    @Test
    fun defaultLabelTextColor_usesCommonMainBlueMapToken() {
        assertEquals(
            GeoVaultColorTokens.Hex.MainBlue,
            GeoVaultColorTokens.Hex.MapLabelText,
        )
        assertEquals(
            GeoVaultColorTokens.Hex.MapLabelText,
            GeoJsonRenderConfig().defaultLabelTextColorHex,
        )
    }
}
