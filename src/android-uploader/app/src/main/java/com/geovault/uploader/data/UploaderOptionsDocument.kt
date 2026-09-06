package com.geovault.uploader.data

import com.geovault.common.settings.GeoVaultLegacySettingsBlob
import kotlinx.serialization.Serializable

@Serializable
data class UploaderOptionsDocument(
    val addFilenameSuffix: Boolean = true,
) {
    companion object {
        const val SCHEMA_VERSION = 1
        const val FILE_NAME = "uploader_options.settings"
        const val LEGACY_FILE_NAME = "geovault_prefs.settings"

        fun fromLegacy(blob: GeoVaultLegacySettingsBlob): UploaderOptionsDocument {
            return UploaderOptionsDocument(
                addFilenameSuffix = blob.boolValues["add_suffix"] ?: true,
            )
        }
    }
}
