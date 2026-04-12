package com.geovault.uploader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilenamePolicyTest {
    @Test
    fun `splitFilename separates basename and extension`() {
        val (base, ext) = FilenamePolicy.splitFilename("track.gpx")
        assertEquals("track", base)
        assertEquals("gpx", ext)
    }

    @Test
    fun `withOptionalSuffix appends suffix before extension`() {
        val value = FilenamePolicy.withOptionalSuffix("import.kml", addSuffix = true)
        assertEquals("import_android_upload.kml", value)
    }

    @Test
    fun `withOptionalSuffix keeps name when disabled`() {
        val value = FilenamePolicy.withOptionalSuffix("import.kml", addSuffix = false)
        assertEquals("import.kml", value)
    }

    @Test
    fun `isSupportedImportType validates known extensions`() {
        assertTrue(FilenamePolicy.isSupportedImportType("route.kmz"))
        assertTrue(FilenamePolicy.isSupportedImportType("route.KML"))
        assertTrue(FilenamePolicy.isSupportedImportType("route.gpx"))
        assertFalse(FilenamePolicy.isSupportedImportType("route.geojson"))
    }
}
