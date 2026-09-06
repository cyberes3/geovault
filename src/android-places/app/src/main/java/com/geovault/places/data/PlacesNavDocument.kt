package com.geovault.places.data

import com.geovault.common.settings.GeoVaultLegacySettingsBlob
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PlacesNavDocument(
    val pendingNavigationIds: List<Int> = emptyList(),
) {
    companion object {
        const val SCHEMA_VERSION = 1
        const val FILE_NAME = "geovault_places_nav.settings"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun fromLegacy(blob: GeoVaultLegacySettingsBlob): PlacesNavDocument {
            val raw = blob.stringValues["pending_navigation_ids"].orEmpty()
            val ids = runCatching {
                if (raw.isBlank() || raw == "[]") {
                    emptyList()
                } else {
                    json.decodeFromString<List<Int>>(raw)
                }
            }.getOrElse { emptyList() }
            return PlacesNavDocument(pendingNavigationIds = ids)
        }
    }
}
