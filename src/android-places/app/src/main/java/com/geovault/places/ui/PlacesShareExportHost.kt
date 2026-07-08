package com.geovault.places.ui

import android.content.Context
import android.content.Intent
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
import androidx.core.content.FileProvider
import com.geovault.common.ui.components.GeoVaultActionSheetDialog
import com.geovault.common.ui.components.GeoVaultActionSheetOption
import com.geovault.common.ui.components.GeoVaultMultiSelectDialog
import com.geovault.common.ui.files.ExportedFileToast
import com.geovault.places.data.PlacesCacheStore
import com.geovault.places.export.PlacesKmzExporter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val KMZ_MIME_TYPE = "application/vnd.google-earth.kmz"

/**
 * Owns the "Share" export flow: pick which points to include (from both the list and map top
 * bars), generate a KMZ client-side from the local cache (so offline/unsynced points are
 * included), then choose to send it via the system share sheet or save it to a chosen location.
 *
 * Rendered unconditionally — like [com.geovault.common.ui.components.GeoVaultShellSettingsOverlayHost] —
 * so the SAF launcher stays registered across recompositions regardless of dialog visibility.
 */
@Composable
fun PlacesShareExportHost(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    cacheStore: PlacesCacheStore,
) {
    val context = LocalContext.current
    var pendingKmzBytes by remember { mutableStateOf<ByteArray?>(null) }
    var showActionSheet by remember { mutableStateOf(false) }

    val saveDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(KMZ_MIME_TYPE)
    ) { uri: Uri? ->
        val bytes = pendingKmzBytes
        pendingKmzBytes = null
        if (uri == null || bytes == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error("Could not open destination for writing")
        }.onSuccess {
            ExportedFileToast.show(
                context = context,
                destinationUri = uri,
                fallbackBaseName = exportFileBaseName(),
                extensionWithoutDot = "kmz",
            )
        }.onFailure {
            Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
        }
    }

    if (visible) {
        val features = remember(visible) { cacheStore.getDisplayFeatures() }
        GeoVaultMultiSelectDialog(
            title = "Select points to export",
            items = features,
            initialSelection = features.toSet(),
            labelFor = { feature -> feature.properties.name?.takeIf { it.isNotBlank() } ?: "(unnamed)" },
            emptyLabel = "No places to export",
            searchable = true,
            selectNoneLabel = "Select none",
            onConfirm = { selected ->
                onDismissRequest()
                if (selected.isEmpty()) {
                    Toast.makeText(context, "No points selected", Toast.LENGTH_SHORT).show()
                } else {
                    pendingKmzBytes = PlacesKmzExporter.buildKmzBytes(features.filter { it in selected })
                    showActionSheet = true
                }
            },
            onDismiss = onDismissRequest,
        )
    }

    if (showActionSheet) {
        GeoVaultActionSheetDialog(
            title = "Share places",
            options = listOf(
                GeoVaultActionSheetOption(
                    label = "Share",
                    onClick = {
                        showActionSheet = false
                        val bytes = pendingKmzBytes
                        pendingKmzBytes = null
                        if (bytes != null) {
                            shareKmzBytes(context, bytes)
                        }
                    },
                ),
                GeoVaultActionSheetOption(
                    label = "Save to device",
                    onClick = {
                        showActionSheet = false
                        saveDocumentLauncher.launch("${exportFileBaseName()}.kmz")
                    },
                ),
            ),
            onDismissRequest = {
                showActionSheet = false
                pendingKmzBytes = null
            },
        )
    }
}

private fun exportFileBaseName(): String =
    "places_export_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"

private fun shareKmzBytes(context: Context, bytes: ByteArray) {
    runCatching {
        val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(exportsDir, "${exportFileBaseName()}.kmz")
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = KMZ_MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share places"))
    }.onFailure {
        Toast.makeText(context, "Share failed", Toast.LENGTH_SHORT).show()
    }
}
