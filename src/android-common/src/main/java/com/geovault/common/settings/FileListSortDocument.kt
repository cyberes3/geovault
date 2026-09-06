package com.geovault.common.settings

import com.geovault.common.sort.GeoVaultFileListSortMode
import kotlinx.serialization.Serializable

@Serializable
data class FileListSortDocument(
    val dataFilesSort: String = GeoVaultFileListSortMode.DEFAULT.name,
    val coordinateSystemsSort: String = GeoVaultFileListSortMode.DEFAULT.name,
) {
    companion object {
        const val SCHEMA_VERSION = 1
        const val FILE_NAME = "geovault_file_list_sort.settings"

        fun fromLegacy(blob: GeoVaultLegacySettingsBlob): FileListSortDocument {
            return FileListSortDocument(
                dataFilesSort = blob.stringValues["data_files_sort"]
                    ?: GeoVaultFileListSortMode.DEFAULT.name,
                coordinateSystemsSort = blob.stringValues["coordinate_systems_sort"]
                    ?: GeoVaultFileListSortMode.DEFAULT.name,
            )
        }
    }
}
