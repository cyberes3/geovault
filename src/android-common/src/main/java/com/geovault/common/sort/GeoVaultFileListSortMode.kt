package com.geovault.common.sort

enum class GeoVaultFileListSortMode(val label: String) {
    NAME_A_TO_Z("File name (A to Z)"),
    NAME_Z_TO_A("File name (Z to A)"),
    MODIFIED_NEWEST("Created (newest first)"),
    MODIFIED_OLDEST("Created (oldest first)"),
    ;

    companion object {
        val DEFAULT: GeoVaultFileListSortMode = NAME_A_TO_Z

        fun fromStored(value: String?): GeoVaultFileListSortMode {
            if (value.isNullOrBlank()) return DEFAULT
            return entries.find { it.name == value } ?: DEFAULT
        }
    }
}

enum class GeoVaultFileListSortScope {
    DATA_FILES,
    COORDINATE_SYSTEMS,
}
