package com.geovault.common.files

import android.net.Uri
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class GeoVaultFileTypeCatalogTest {

    private val catalog = GeoVaultFileTypeCatalog(
        listOf(
            GeoVaultFileType("kml", setOf("application/vnd.google-earth.kml+xml")),
            GeoVaultFileType("csv", setOf("text/csv", "application/csv")),
        ),
    )

    @Test
    fun `isSupportedFilename matches extension case-insensitively`() {
        assertTrue(catalog.isSupportedFilename("job.kml"))
        assertTrue(catalog.isSupportedFilename("job.KML"))
        assertTrue(catalog.isSupportedFilename("points.csv"))
        assertFalse(catalog.isSupportedFilename("job.txt"))
        assertFalse(catalog.isSupportedFilename("kml"))
        assertFalse(catalog.isSupportedFilename(".kml"))
        assertFalse(catalog.isSupportedFilename(""))
    }

    @Test
    fun `extensionFor returns lowercase extension or null`() {
        assertEquals("kml", catalog.extensionFor("a.KML"))
        assertEquals("csv", catalog.extensionFor("nested.file.csv"))
        assertNull(catalog.extensionFor("noext"))
        assertNull(catalog.extensionFor(".hidden"))
        assertNull(catalog.extensionFor("trailing."))
    }

    @Test
    fun `mimeTypes are distinct and first-seen`() {
        assertArrayEquals(
            arrayOf("application/vnd.google-earth.kml+xml", "text/csv", "application/csv"),
            catalog.mimeTypes,
        )
    }

    @Test
    fun `primaryMimeType returns the first MIME for an extension`() {
        assertEquals("text/csv", catalog.primaryMimeType("csv"))
        assertEquals("application/vnd.google-earth.kml+xml", catalog.primaryMimeType(".kml"))
        assertNull(catalog.primaryMimeType("txt"))
    }

    @Test
    fun `stripSupportedExtension removes a matching suffix`() {
        assertEquals("Job 42", catalog.stripSupportedExtension("Job 42.kml"))
        assertEquals("Job 42", catalog.stripSupportedExtension("Job 42.KML"))
        assertEquals("notes.txt", catalog.stripSupportedExtension("notes.txt"))
    }

    @Test
    fun `typeForMime matches exact types and ignores charset and wildcards`() {
        assertEquals("csv", catalog.typeForMime("text/csv")?.extension)
        assertEquals("csv", catalog.typeForMime("TEXT/CSV; charset=utf-8")?.extension)
        assertNull(catalog.typeForMime("text/*"))
        assertNull(catalog.typeForMime("*/*"))
        assertNull(catalog.typeForMime("application/pdf"))
    }

    @Test
    fun `preferredFileName adds extension from mime when the name has none`() {
        assertEquals("job.kml", catalog.preferredFileName("job.kml", "text/csv"))
        assertEquals("msf:12.csv", catalog.preferredFileName("msf:12", "text/csv"))
        assertEquals("msf:12", catalog.preferredFileName("msf:12", "application/pdf"))
    }

    @Test
    fun `classify accepts a supported mime when the display name has no extension`() {
        val unnamed = Uri.parse("content://downloads/msf:12")
        val pdf = Uri.parse("content://test/b.pdf")
        val result = catalog.classify(
            listOf(unnamed, pdf),
            displayNameOf = { uri -> uri.lastPathSegment ?: "file" },
            mimeTypeOf = { uri -> if (uri == unnamed) "text/csv" else "application/pdf" },
        )
        assertEquals(listOf(unnamed), result.supported)
        assertEquals(listOf("b.pdf"), result.rejectedFileNames)
    }

    @Test
    fun `classify partitions by display name`() {
        val kml = Uri.parse("content://test/a.kml")
        val pdf = Uri.parse("content://test/b.pdf")
        val csv = Uri.parse("content://test/c.csv")
        val result = catalog.classify(listOf(kml, pdf, csv)) { uri ->
            uri.lastPathSegment ?: "file"
        }
        assertEquals(listOf(kml, csv), result.supported)
        assertEquals(listOf("b.pdf"), result.rejectedFileNames)
    }

    @Test
    fun `upload catalog still recognizes kml kmz gpx`() {
        assertTrue(GeoVaultUploadFileTypes.isSupportedFilename("track.gpx"))
        assertTrue(GeoVaultUploadFileTypes.isSupportedFilename("layer.kmz"))
        assertFalse(GeoVaultUploadFileTypes.isSupportedFilename("layer.dxf"))
        assertTrue(GeoVaultUploadFileTypes.supportedMimeTypes.contains("application/gpx+xml"))
    }
}
