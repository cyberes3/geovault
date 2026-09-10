package com.geovault.common.ui.files

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.geovault.common.files.GeoVaultFileExport
import com.geovault.common.files.GeoVaultSafExportSession
import com.geovault.common.ui.components.GeoVaultShareSaveActionSheet

@Composable
fun GeoVaultShareSaveExportHost(
    request: GeoVaultShareSaveExportRequest?,
    onConsumed: () -> Unit,
    writeFailedMessage: String = GeoVaultSafExportSession.DEFAULT_WRITE_FAILED_MESSAGE,
    onWriteFailed: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val fileExport = remember(context) { GeoVaultFileExport(context) }
    val launchSave = LocalGeoVaultSafExport.current
    if (request == null) {
        return
    }
    GeoVaultShareSaveActionSheet(
        title = request.title,
        saveLabel = request.saveLabel,
        onShare = {
            fileExport.shareBytes(
                bytes = request.bytes,
                fileName = request.fileName,
                mimeType = request.mimeType,
                chooserTitle = request.chooserTitle,
            )
        },
        onSave = {
            launchSave.launch(
                request = request.toSafRequest(),
                writeFailedMessage = writeFailedMessage,
                onWriteFailed = onWriteFailed,
            )
        },
        onDismissRequest = onConsumed,
    )
}
