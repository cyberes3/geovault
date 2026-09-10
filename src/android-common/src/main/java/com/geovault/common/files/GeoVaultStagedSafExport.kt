package com.geovault.common.files

import java.io.File
import kotlinx.serialization.Serializable

@Serializable
data class GeoVaultStagedSafExportMeta(
    val suggestedFileName: String,
    val fallbackBaseName: String,
    val extensionWithoutDot: String,
    val mimeType: String,
    val writeFailedMessage: String,
)

/**
 * Disk-backed Save payload. [payloadFile] is the staged bytes; metadata is what the
 * picker and success/failure UI need after process death.
 */
data class GeoVaultStagedSafExport(
    val payloadFile: File,
    val suggestedFileName: String,
    val fallbackBaseName: String,
    val extensionWithoutDot: String,
    val mimeType: String,
    val writeFailedMessage: String,
) {
    constructor(payloadFile: File, meta: GeoVaultStagedSafExportMeta) : this(
        payloadFile = payloadFile,
        suggestedFileName = meta.suggestedFileName,
        fallbackBaseName = meta.fallbackBaseName,
        extensionWithoutDot = meta.extensionWithoutDot,
        mimeType = meta.mimeType,
        writeFailedMessage = meta.writeFailedMessage,
    )
}
