package com.geovault.common.settings

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class GeoVaultLegacySettingsBlob(
    val schemaVersion: Int = 1,
    val boolValues: Map<String, Boolean> = emptyMap(),
    val stringValues: Map<String, String> = emptyMap(),
    val intValues: Map<String, Int> = emptyMap(),
    val longValues: Map<String, Long> = emptyMap(),
    val floatValues: Map<String, Float> = emptyMap(),
) {
    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun isLegacyMapBlob(element: JsonElement): Boolean {
            val obj = element as? JsonObject ?: return false
            if (obj.containsKey("payload")) return false
            return obj.containsKey("stringValues") ||
                obj.containsKey("boolValues") ||
                obj.containsKey("intValues") ||
                obj.containsKey("longValues") ||
                obj.containsKey("floatValues")
        }

        fun parse(text: String): GeoVaultLegacySettingsBlob? {
            if (text.isBlank()) return null
            return runCatching {
                val element = json.parseToJsonElement(text)
                if (!isLegacyMapBlob(element)) return null
                json.decodeFromJsonElement(serializer(), element)
            }.getOrNull()
        }

        fun readFrom(file: File): GeoVaultLegacySettingsBlob? {
            if (!file.exists() || file.length() == 0L) return null
            return parse(file.readText())
        }
    }
}
