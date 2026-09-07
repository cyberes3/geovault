package com.geovault.common.files

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoVaultFilenameTest {
    @Test
    fun splitBaseAndExtension_separatesBasenameAndExtension() {
        val (base, ext) = GeoVaultFilename.splitBaseAndExtension("track.gpx")
        assertEquals("track", base)
        assertEquals("gpx", ext)
    }

    @Test
    fun splitBaseAndExtension_keepsNameWithoutExtension() {
        val (base, ext) = GeoVaultFilename.splitBaseAndExtension("readme")
        assertEquals("readme", base)
        assertEquals("", ext)
    }
}
