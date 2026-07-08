package com.geovault.common.maps.kml

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A single point placemark to export to KML/KMZ. Callers build their own [description] text
 * (e.g. survey coordinate metadata, or a Places address/notes block) since that content is
 * app-specific; this only handles the generic KML structure, escaping, and KMZ packaging.
 */
data class GeoVaultKmlPlacemark(
    val name: String,
    val description: String? = null,
    val longitude: Double,
    val latitude: Double,
    val altitude: Double? = null,
)

/**
 * Shared KML 2.2 writer counterpart to [KmlGeometryPullParser]. Supports a minimal, practical
 * subset: a `<Document>` of point `<Placemark>` elements with a name and optional description,
 * which is all any app in this monorepo currently needs to export.
 */
object GeoVaultKmlExporter {

    /** Builds a KML document string with one `<Placemark>` per entry in [placemarks]. */
    fun buildKmlDocument(documentName: String, placemarks: List<GeoVaultKmlPlacemark>): String {
        val placemarksXml = placemarks.joinToString("") { placemark -> placemarkXml(placemark) }
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n")
            append("  <Document>\n")
            append("    <name>${escape(documentName)}</name>\n")
            append(placemarksXml)
            append("  </Document>\n")
            append("</kml>\n")
        }
    }

    /**
     * Builds a KMZ archive (as bytes) containing [buildKmlDocument]'s output as `doc.kml`,
     * matching the `doc.kml` convention used by the backend's KMZ export.
     */
    fun buildKmzBytes(documentName: String, placemarks: List<GeoVaultKmlPlacemark>): ByteArray {
        val kml = buildKmlDocument(documentName, placemarks)
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            zip.putNextEntry(ZipEntry("doc.kml"))
            zip.write(kml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return buffer.toByteArray()
    }

    private fun placemarkXml(placemark: GeoVaultKmlPlacemark): String {
        val coordinates = if (placemark.altitude != null) {
            "${placemark.longitude},${placemark.latitude},${placemark.altitude}"
        } else {
            "${placemark.longitude},${placemark.latitude}"
        }
        return buildString {
            append("  <Placemark>\n")
            append("    <name>${escape(placemark.name)}</name>\n")
            placemark.description?.takeIf { it.isNotBlank() }?.let { description ->
                append("    <description>${escape(description)}</description>\n")
            }
            append("    <Point><coordinates>$coordinates</coordinates></Point>\n")
            append("  </Placemark>\n")
        }
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
