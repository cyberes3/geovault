package com.geovault.places.export

import com.geovault.common.geo.CoordinateParser
import com.geovault.common.maps.kml.GeoVaultKmlExporter
import com.geovault.common.maps.kml.GeoVaultKmlPlacemark
import com.geovault.places.model.Place
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class PlacesExportFormat {
    KMZ,
    PLAIN_TEXT,
}

object PlacesExporter {
    private const val KMZ_DOCUMENT_NAME = "GeoVault Places"

    fun export(places: List<Place>, format: PlacesExportFormat): ByteArray {
        return when (format) {
            PlacesExportFormat.KMZ -> buildKmzBytes(places)
            PlacesExportFormat.PLAIN_TEXT -> buildPlainText(places).toByteArray(Charsets.UTF_8)
        }
    }

    fun buildKmzBytes(places: List<Place>): ByteArray {
        val placemarks = places.mapNotNull { place -> placemarkOrNull(place) }
        return GeoVaultKmlExporter.buildKmzBytes(KMZ_DOCUMENT_NAME, placemarks)
    }

    fun buildPlainText(places: List<Place>, exportedAt: String = defaultExportedAt()): String {
        return buildString {
            appendLine("GeoVault emergency export - $exportedAt")
            appendLine()
            places.forEach { append(plainTextBlock(it)) }
        }
    }

    fun plainTextBlock(place: Place): String {
        val location = place.content.location
        val coordsLine = CoordinateParser.formatLatLon(location.latitude, location.longitude)
        return buildString {
            appendLine(place.content.name.ifBlank { "(unnamed)" })
            appendLine(place.createdAt.orEmpty())
            appendLine(coordsLine)
            appendLine(place.content.address.orEmpty())
            appendLine(place.content.description)
            appendLine()
        }
    }

    private fun placemarkOrNull(place: Place): GeoVaultKmlPlacemark? {
        val location = place.content.location
        if (!location.isValidGeographic()) return null
        return GeoVaultKmlPlacemark(
            name = place.content.name.ifBlank { "Unnamed Place" },
            description = kmlDescription(place),
            longitude = location.longitude,
            latitude = location.latitude,
        )
    }

    private fun kmlDescription(place: Place): String? {
        val parts = mutableListOf<String>()
        place.content.description.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        place.content.address?.takeIf { it.isNotBlank() }?.let { parts.add("Address: $it") }
        parts.add(
            "Coordinates: ${CoordinateParser.formatLatLon(place.content.location.latitude, place.content.location.longitude)}",
        )
        place.createdAt?.takeIf { it.isNotBlank() }?.let { parts.add("Created: $it") }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    private fun defaultExportedAt(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
    }
}
