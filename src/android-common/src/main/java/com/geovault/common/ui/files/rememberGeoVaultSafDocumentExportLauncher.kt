package com.geovault.common.ui.files

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
)

@Composable
fun rememberGeoVaultSafDocumentExportLauncher(
    mimeType: String,
    writeFailedMessage: String = "Export failed",
): (GeoVaultSafExportRequest) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<GeoVaultSafExportRequest?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(mimeType)
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
            Toast.makeText(context.applicationContext, writeFailedMessage, Toast.LENGTH_LONG).show()
        }
    }
    return { request ->
        pending = request
        launcher.launch(request.suggestedFileName)
    }
}
