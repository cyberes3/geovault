package com.geovault.common.ui.files

data class GeoVaultShareSaveExportRequest(
    val title: String,
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
    val chooserTitle: String = title,
    val saveLabel: String = "Save",
) {
    fun toSafRequest(): GeoVaultSafExportRequest {
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
        val fallbackBaseName = if (extension.isEmpty()) {
            fileName
        } else {
            fileName.removeSuffix(".$extension")
        }
        return GeoVaultSafExportRequest(
            bytes = bytes,
            suggestedFileName = fileName,
            fallbackBaseName = fallbackBaseName.ifBlank { fileName },
            extensionWithoutDot = extension.ifBlank { "bin" },
            mimeType = mimeType,
        )
    }

    companion object {
        fun fromSaf(
            title: String,
            saf: GeoVaultSafExportRequest,
            chooserTitle: String = title,
            saveLabel: String = "Save",
        ): GeoVaultShareSaveExportRequest {
            val mime = saf.mimeType
                ?: error("GeoVaultSafExportRequest.mimeType is required for share/save")
            return GeoVaultShareSaveExportRequest(
                title = title,
                fileName = saf.suggestedFileName,
                mimeType = mime,
                bytes = saf.bytes,
                chooserTitle = chooserTitle,
                saveLabel = saveLabel,
            )
        }
    }
}
