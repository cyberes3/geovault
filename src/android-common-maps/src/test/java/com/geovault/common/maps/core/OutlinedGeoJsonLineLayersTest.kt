package com.geovault.common.maps.core

import org.junit.Assert.assertEquals
import org.junit.Test

class OutlinedGeoJsonLineLayersTest {
    @Test
    fun colorIntToHex6_masksAlphaChannel() {
        assertEquals("#00AA11", OutlinedGeoJsonLineLayers.colorIntToHex6(0xFF00AA11.toInt()))
        assertEquals("#000000", OutlinedGeoJsonLineLayers.colorIntToHex6(0xFF000000.toInt()))
    }
}
