package com.geovault.places.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.geovault.common.files.GeoVaultExportFileNames
import com.geovault.common.files.GeoVaultFileExport
import com.geovault.common.ui.GeoVaultAppSnackbarLayer
import com.geovault.common.ui.components.GeoVaultActionSheetDialog
import com.geovault.common.ui.components.GeoVaultActionSheetOption
import com.geovault.common.ui.components.GeoVaultMultiSelectDialog
import com.geovault.common.ui.files.GeoVaultSafExportRequest
import com.geovault.common.ui.files.rememberGeoVaultSafDocumentExportLauncher
import com.geovault.common.ui.snackbar.GeoVaultSnackbarModel
import com.geovault.places.data.PlacesStore
import com.geovault.places.export.PlacesKmzExporter

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
    placesStore: PlacesStore,
) {
    val context = LocalContext.current
    val fileExport = remember(context) { GeoVaultFileExport(context) }
    var pendingKmzBytes by remember { mutableStateOf<ByteArray?>(null) }
    var showActionSheet by remember { mutableStateOf(false) }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }
    val launchSaveDocument = rememberGeoVaultSafDocumentExportLauncher(KMZ_MIME_TYPE)

    if (visible) {
        val features = remember(visible) { placesStore.getDisplayFeatures() }
        GeoVaultMultiSelectDialog(
            title = "Select points to export",
            items = features,
            initialSelection = features.toSet(),
            labelFor = { feature -> feature.properties.name?.takeIf { it.isNotBlank() } ?: "(unnamed)" },
            emptyLabel = "No places to export",
            searchable = true,
            selectNoneLabel = "Select none",
            confirmText = "Export",
            onConfirm = { selected ->
                onDismissRequest()
                if (selected.isEmpty()) {
                    snackbarMessage = "No points selected"
                } else {
                    pendingKmzBytes = PlacesKmzExporter.buildKmzBytes(features.filter { it in selected })
                    showActionSheet = true
                }
            },
            onDismissRequest = onDismissRequest,
        )
    }

    if (showActionSheet) {
        val baseName = GeoVaultExportFileNames.timestamped("places_export")
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
                            fileExport.shareBytes(
                                bytes = bytes,
                                fileName = "$baseName.kmz",
                                mimeType = KMZ_MIME_TYPE,
                                chooserTitle = "Share places",
                            )
                        }
                    },
                ),
                GeoVaultActionSheetOption(
                    label = "Save to device",
                    onClick = {
                        showActionSheet = false
                        val bytes = pendingKmzBytes
                        pendingKmzBytes = null
                        if (bytes != null) {
                            launchSaveDocument(
                                GeoVaultSafExportRequest(
                                    bytes = bytes,
                                    suggestedFileName = "$baseName.kmz",
                                    fallbackBaseName = baseName,
                                    extensionWithoutDot = "kmz",
                                )
                            )
                        }
                    },
                ),
            ),
            onDismissRequest = {
                showActionSheet = false
                pendingKmzBytes = null
            },
        )
    }

    snackbarMessage?.let { message ->
        GeoVaultAppSnackbarLayer(
            snackbar = GeoVaultSnackbarModel(id = message, message = message),
            onDismissSnackbar = { snackbarMessage = null },
            update = null,
            onDismissUpdate = {},
        )
    }
}
