package com.geovault.common.ui.files

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

data class GeoVaultSafExportRequest(
    val bytes: ByteArray,
    val suggestedFileName: String,
    val fallbackBaseName: String,
    val extensionWithoutDot: String,
    val mimeType: String? = null,
)

@Composable
fun rememberGeoVaultSafDocumentExportLauncher(
    mimeType: String,
    writeFailedMessage: String = "Export failed",
    onWriteFailed: ((String) -> Unit)? = null,
): (GeoVaultSafExportRequest) -> Unit {
    return rememberGeoVaultSafDocumentExportLauncher(
        defaultMimeType = mimeType,
        writeFailedMessage = writeFailedMessage,
        onWriteFailed = onWriteFailed,
    )
}

/**
 * One SAF create-document launcher that takes MIME from [GeoVaultSafExportRequest.mimeType]
 * (or [defaultMimeType]) so a host can export several formats without stacked contracts.
 */
@Composable
fun rememberGeoVaultSafDocumentExportLauncher(
    writeFailedMessage: String = "Export failed",
    onWriteFailed: ((String) -> Unit)? = null,
    defaultMimeType: String? = null,
): (GeoVaultSafExportRequest) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<GeoVaultSafExportRequest?>(null) }
    val launcher = rememberLauncherForActivityResult(
        GeoVaultCreateDocumentWithMimeContract(),
    ) { uri: Uri? ->
        val request = pending
        pending = null
        if (uri == null || request == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(request.bytes) }
                ?: error("Could not open destination for writing")
        }.onSuccess {
            ExportedFileToast.show(
                context = context,
                destinationUri = uri,
                fallbackBaseName = request.fallbackBaseName,
                extensionWithoutDot = request.extensionWithoutDot,
            )
        }.onFailure {
            val message = writeFailedMessage
            if (onWriteFailed != null) {
                onWriteFailed(message)
            } else {
                Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
            }
        }
    }
    return { request ->
        val mime = request.mimeType ?: defaultMimeType
            ?: error("GeoVaultSafExportRequest.mimeType is required when no default MIME is set")
        pending = request
        launcher.launch(
            GeoVaultCreateDocumentRequest(
                suggestedName = request.suggestedFileName,
                mimeType = mime,
            ),
        )
    }
}
