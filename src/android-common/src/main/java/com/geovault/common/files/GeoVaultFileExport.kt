package com.geovault.common.files

import android.content.Context
import android.net.Uri
import com.geovault.common.ui.files.ExportedFileToast

class GeoVaultFileExport(context: Context) {
    private val appContext = context.applicationContext
    private val downloads = GeoVaultDownloadsWriter(appContext)

    suspend fun saveToDownloads(
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
        showToast: Boolean = true,
    ): Result<Uri> {
        val result = downloads.write(displayName, mimeType, bytes)
        if (showToast) {
            result.getOrNull()?.let { uri ->
                val ext = displayName.substringAfterLast('.', missingDelimiterValue = "")
                    .takeIf { it.isNotBlank() && it != displayName }
                ExportedFileToast.show(appContext, uri, displayName, ext)
            }
        }
        return result
    }

    fun shareBytes(
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
        chooserTitle: String = "Share",
    ) {
        GeoVaultOutgoingShare.shareBytes(appContext, bytes, fileName, mimeType, chooserTitle)
    }

    fun shareText(text: String, chooserTitle: String? = null) {
        GeoVaultOutgoingShare.shareText(appContext, text, chooserTitle)
    }
}
