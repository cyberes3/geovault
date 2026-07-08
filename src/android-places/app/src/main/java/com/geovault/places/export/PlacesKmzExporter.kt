package com.geovault.places.export

import com.geovault.common.maps.kml.GeoVaultKmlExporter
import com.geovault.common.maps.kml.GeoVaultKmlPlacemark
import com.geovault.places.model.Feature

/**
 * Builds a KMZ archive from selected Places [Feature]s using the shared [GeoVaultKmlExporter].
 * Generation is entirely client-side (not a backend call) so offline/unsynced places — which
 * may not exist on the server yet — can still be included in the export.
 */
object PlacesKmzExporter {
    private const val DOCUMENT_NAME = "GeoVault Places"

    fun buildKmzBytes(features: List<Feature>): ByteArray {
        val placemarks = features.mapNotNull { feature -> placemarkOrNull(feature) }
        return GeoVaultKmlExporter.buildKmzBytes(DOCUMENT_NAME, placemarks)
    }

    private fun placemarkOrNull(feature: Feature): GeoVaultKmlPlacemark? {
        val coords = feature.geometry.coordinates
        if (coords.size < 2) return null
        return GeoVaultKmlPlacemark(
            name = feature.properties.name?.takeIf { it.isNotBlank() } ?: "Unnamed Place",
            description = buildDescription(feature),
            longitude = coords[0],
            latitude = coords[1],
            altitude = coords.getOrNull(2),
        )
    }

    private fun buildDescription(feature: Feature): String? {
        val properties = feature.properties
        val coords = feature.geometry.coordinates
        val parts = mutableListOf<String>()
        properties.description?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        properties.address?.takeIf { it.isNotBlank() }?.let { parts.add("Address: $it") }
        if (coords.size >= 2) {
            parts.add("Coordinates: ${coords[1]}, ${coords[0]}")
        }
        properties.created_at?.takeIf { it.isNotBlank() }?.let { parts.add("Created: $it") }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }
}
