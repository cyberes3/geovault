package com.geovault.common.files

/**
 * In-memory handoff from an export preparer to Share/Save.
 *
 * Save persists this to [GeoVaultSafPendingExportStore] before the SAF picker opens so the
 * write does not depend on Compose state surviving the document UI.
 */
data class GeoVaultSafExportRequest(
    val bytes: ByteArray,
    val suggestedFileName: String,
    val fallbackBaseName: String,
    val extensionWithoutDot: String,
    val mimeType: String,
)
