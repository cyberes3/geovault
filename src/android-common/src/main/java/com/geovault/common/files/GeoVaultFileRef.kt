package com.geovault.common.files

import android.net.Uri

data class GeoVaultFileRef(
    val uri: Uri,
    val displayName: String,
    val mimeType: String?,
    val extension: String?,
    val sizeBytes: Long,
    val source: Source,
) {
    enum class Source {
        Intent,
        Picker,
        Staged,
    }
}
