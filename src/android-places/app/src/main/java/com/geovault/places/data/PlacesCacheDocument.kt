package com.geovault.places.data

import com.geovault.common.settings.GeoVaultLegacySettingsBlob
import com.geovault.places.model.Feature
import com.geovault.places.model.FeatureCollection
import com.geovault.places.model.OfflineFeature
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PlacesCacheDocument(
    val cached: List<Feature> = emptyList(),
    val offline: List<OfflineFeature> = emptyList(),
    val lastSyncMillis: Long = 0L,
) {
    fun sanitized(): PlacesCacheDocument {
        val valid = PlacesStore.retainValidOfflineEntries(offline)
        return if (valid.size == offline.size) this else copy(offline = valid)
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val FILE_NAME = "geovault_places_cache.settings"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun fromLegacy(blob: GeoVaultLegacySettingsBlob): PlacesCacheDocument {
            return PlacesCacheDocument(
                cached = parseCached(blob.stringValues["cached_places"]),
                offline = PlacesStore.retainValidOfflineEntries(
                    parseOffline(blob.stringValues["offline_places"])
                ),
                lastSyncMillis = blob.longValues["last_sync_time"] ?: 0L,
            )
        }

        private fun parseCached(raw: String?): List<Feature> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                json.decodeFromString(FeatureCollection.serializer(), raw).features
            }.getOrElse { emptyList() }
        }

        private fun parseOffline(raw: String?): List<OfflineFeature> {
            if (raw.isNullOrBlank() || raw == "[]") return emptyList()
            return runCatching {
                json.decodeFromString<List<OfflineFeature>>(raw)
            }.getOrElse { emptyList() }
        }
    }
}
