package com.geovault.common.maps.kml

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Pure readers for KML placemark `propertiesJson` blobs stored alongside overlay geometries.
 *
 * Keys written at import are lowercased ExtendedData / SchemaData names (e.g. `Code` → `code`).
 */
object KmlFeatureProperties {

    private val JSON = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Returns the trimmed `code` property, or null when missing / blank / unparseable.
     */
    fun code(propertiesJson: String?): String? {
        val obj = parseObjectOrNull(propertiesJson) ?: return null
        val raw = obj["code"] ?: return null
        if (raw !is JsonPrimitive) return null
        return raw.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun parseObjectOrNull(propertiesJson: String?): JsonObject? {
        if (propertiesJson.isNullOrBlank()) return null
        return runCatching { JSON.parseToJsonElement(propertiesJson) as? JsonObject }.getOrNull()
    }
}
