package com.geovault.common.maps.core

import com.geovault.common.settings.GeoVaultLegacySettingsBlob
import kotlinx.serialization.Serializable

@Serializable
data class MapSourceDocument(
    val selectedSourceId: String = "",
) {
    companion object {
        const val SCHEMA_VERSION = 1
        const val FILE_NAME = "geovault_map_source.settings"

        fun fromLegacy(blob: GeoVaultLegacySettingsBlob): MapSourceDocument {
            return MapSourceDocument(
                selectedSourceId = blob.stringValues["selected_map_source"].orEmpty(),
            )
        }
    }
}
