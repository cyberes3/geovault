package com.geovault.common.maps.kml

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoVaultKmlExporterTest {

    @Test
    fun buildKmlDocument_includesNameDescriptionAndCoordinatesPerPlacemark() {
        val kml = GeoVaultKmlExporter.buildKmlDocument(
            documentName = "My Export",
            placemarks = listOf(
                GeoVaultKmlPlacemark(
                    name = "Point A",
                    description = "Some notes",
                    longitude = -105.1,
                    latitude = 40.2,
                ),
            ),
        )

        assertTrue(kml.contains("<name>My Export</name>"))
        assertTrue(kml.contains("<name>Point A</name>"))
        assertTrue(kml.contains("<description>Some notes</description>"))
        assertTrue(kml.contains("<Point><coordinates>-105.1,40.2</coordinates></Point>"))
    }

    @Test
    fun buildKmlDocument_includesAltitudeWhenPresent() {
        val kml = GeoVaultKmlExporter.buildKmlDocument(
            documentName = "Doc",
            placemarks = listOf(
                GeoVaultKmlPlacemark(name = "P", longitude = 1.0, latitude = 2.0, altitude = 3.5),
            ),
        )

        assertTrue(kml.contains("<Point><coordinates>1.0,2.0,3.5</coordinates></Point>"))
    }

    @Test
    fun buildKmlDocument_omitsDescriptionWhenBlankOrNull() {
        val kml = GeoVaultKmlExporter.buildKmlDocument(
            documentName = "Doc",
            placemarks = listOf(
                GeoVaultKmlPlacemark(name = "P1", description = null, longitude = 1.0, latitude = 2.0),
                GeoVaultKmlPlacemark(name = "P2", description = "  ", longitude = 3.0, latitude = 4.0),
            ),
        )

        assertTrue(!kml.contains("<description>"))
    }

    @Test
    fun buildKmlDocument_escapesXmlSpecialCharacters() {
        val kml = GeoVaultKmlExporter.buildKmlDocument(
            documentName = "Doc",
            placemarks = listOf(
                GeoVaultKmlPlacemark(
                    name = "A & B <tag> \"quoted\" 'apos'",
                    longitude = 1.0,
                    latitude = 2.0,
                ),
            ),
        )

        assertTrue(kml.contains("A &amp; B &lt;tag&gt; &quot;quoted&quot; &apos;apos&apos;"))
    }

    @Test
    fun buildKmzBytes_producesZipWithDocKmlEntryContainingTheDocument() {
        val bytes = GeoVaultKmlExporter.buildKmzBytes(
            documentName = "Zipped",
            placemarks = listOf(
                GeoVaultKmlPlacemark(name = "Point A", longitude = -1.0, latitude = 2.0),
            ),
        )

        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            val entry = zip.nextEntry
            assertEquals("doc.kml", entry?.name)
            val content = zip.readBytes().toString(Charsets.UTF_8)
            assertTrue(content.contains("<name>Zipped</name>"))
            assertTrue(content.contains("<name>Point A</name>"))
            assertNull(zip.nextEntry)
        }
    }
}
