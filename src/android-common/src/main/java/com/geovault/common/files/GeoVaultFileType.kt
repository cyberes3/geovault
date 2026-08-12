package com.geovault.common.files

/**
 * One file kind an app is willing to open, pick, or share: a lowercase extension plus the
 * MIME types providers commonly attach to it.
 */
data class GeoVaultFileType(
    val extension: String,
    val mimeTypes: Set<String>,
) {
    init {
        require(extension.isNotBlank()) { "extension must not be blank" }
        require(extension == extension.lowercase()) { "extension must be lowercase" }
        require(!extension.startsWith('.')) { "extension must not include a leading dot" }
        require(mimeTypes.isNotEmpty()) { "mimeTypes must not be empty" }
    }
}
