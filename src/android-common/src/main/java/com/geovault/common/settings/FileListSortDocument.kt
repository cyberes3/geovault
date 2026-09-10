package com.geovault.common.settings

import com.geovault.common.sort.GeoVaultFileListSortMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

@Serializable
data class FileListSortDocument(
    val modesByScope: Map<String, String> = emptyMap(),
) {
    companion object {
        const val SCHEMA_VERSION = 2
        const val FILE_NAME = "geovault_file_list_sort.settings"
        const val SCOPE_DATA_FILES = "data_files"
        const val SCOPE_COORDINATE_SYSTEMS = "coordinate_systems"

        fun fromLegacy(blob: GeoVaultLegacySettingsBlob): FileListSortDocument {
            return FileListSortDocument(
                modesByScope = buildMap {
                    blob.stringValues["data_files_sort"]?.let { put(SCOPE_DATA_FILES, it) }
                    blob.stringValues["coordinate_systems_sort"]?.let { put(SCOPE_COORDINATE_SYSTEMS, it) }
                },
            )
        }
    }
}

object FileListSortV1ToV2Migration : GeoVaultDocumentMigration {
    override val fromVersion: Int = 1

    override fun migrate(json: JsonObject): JsonObject {
        val modes = buildJsonObject {
            json["dataFilesSort"]?.let { put(FileListSortDocument.SCOPE_DATA_FILES, it) }
            json["coordinateSystemsSort"]?.let { put(FileListSortDocument.SCOPE_COORDINATE_SYSTEMS, it) }
        }
        return buildJsonObject {
            put("modesByScope", modes)
        }
    }
}

fun FileListSortDocument.modeName(scopeKey: String): String {
    return modesByScope[scopeKey] ?: GeoVaultFileListSortMode.DEFAULT.name
}
