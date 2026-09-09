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
import com.geovault.common.ui.components.GeoVaultMultiSelectDialog
import com.geovault.common.ui.components.GeoVaultShareSaveActionSheet
import com.geovault.common.ui.files.GeoVaultSafExportRequest
import com.geovault.common.ui.files.rememberGeoVaultSafDocumentExportLauncher
import com.geovault.common.ui.snackbar.GeoVaultSnackbarModel
import com.geovault.places.data.PlacesStore
import com.geovault.places.domain.PlacesListProjection
import com.geovault.places.export.PlacesExporter

private const val KMZ_MIME_TYPE = "application/vnd.google-earth.kmz"

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
        val places = remember(visible) { PlacesListProjection.exportable(placesStore.places()) }
        GeoVaultMultiSelectDialog(
            title = "Select points to export",
            items = places,
            initialSelection = places.toSet(),
            labelFor = { place -> place.content.name.ifBlank { "(unnamed)" } },
            emptyLabel = "No places to export",
            searchable = true,
            selectNoneLabel = "Select none",
            confirmText = "Export",
            onConfirm = { selected ->
                onDismissRequest()
                if (selected.isEmpty()) {
                    snackbarMessage = "No points selected"
                } else {
                    pendingKmzBytes = PlacesExporter.buildKmzBytes(places.filter { it in selected })
                    showActionSheet = true
                }
            },
            onDismissRequest = onDismissRequest,
        )
    }

    if (showActionSheet) {
        val baseName = GeoVaultExportFileNames.timestamped("places_export")
        GeoVaultShareSaveActionSheet(
            title = "Share places",
            saveLabel = "Save to device",
            onShare = {
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
            onSave = {
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
