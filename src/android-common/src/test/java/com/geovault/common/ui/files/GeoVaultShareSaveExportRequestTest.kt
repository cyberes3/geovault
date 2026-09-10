package com.geovault.common.ui.files

import com.geovault.common.files.GeoVaultSafExportRequest
import org.junit.Assert.assertEquals
import org.junit.Test

class GeoVaultShareSaveExportRequestTest {

    @Test
    fun toSafRequest_splitsNameAndKeepsMime() {
        val request = GeoVaultShareSaveExportRequest(
            title = "Export KML",
            fileName = "job.kml",
            mimeType = "application/vnd.google-earth.kml+xml",
            bytes = byteArrayOf(1, 2, 3),
            chooserTitle = "Share KML",
        )
        val saf = request.toSafRequest()
        assertEquals("job.kml", saf.suggestedFileName)
        assertEquals("job", saf.fallbackBaseName)
        assertEquals("kml", saf.extensionWithoutDot)
        assertEquals("application/vnd.google-earth.kml+xml", saf.mimeType)
    }

    @Test
    fun fromSaf_roundTripsFileNameAndBytes() {
        val saf = GeoVaultSafExportRequest(
            bytes = byteArrayOf(9),
            suggestedFileName = "places_export.kmz",
            fallbackBaseName = "places_export",
            extensionWithoutDot = "kmz",
            mimeType = "application/vnd.google-earth.kmz",
        )
        val request = GeoVaultShareSaveExportRequest.fromSaf(
            title = "Share places",
            saf = saf,
            chooserTitle = "Share places",
        )
        assertEquals("places_export.kmz", request.fileName)
        assertEquals("application/vnd.google-earth.kmz", request.mimeType)
        assertEquals(1, request.bytes.size)
        assertEquals(saf.suggestedFileName, request.toSafRequest().suggestedFileName)
    }
}
