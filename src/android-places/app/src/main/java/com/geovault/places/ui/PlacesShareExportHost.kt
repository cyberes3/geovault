package com.geovault.places.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.geovault.common.files.GeoVaultExportFileNames
import com.geovault.common.files.GeoVaultStandardFileTypes
import com.geovault.common.ui.GeoVaultAppSnackbarLayer
import com.geovault.common.ui.components.GeoVaultMultiSelectDialog
import com.geovault.common.ui.files.GeoVaultShareSaveExportHost
import com.geovault.common.ui.files.GeoVaultShareSaveExportRequest
import com.geovault.common.ui.snackbar.GeoVaultSnackbarModel
import com.geovault.places.data.PlacesStore
import com.geovault.places.domain.PlacesListProjection
import com.geovault.places.export.PlacesExporter

@Composable
fun PlacesShareExportHost(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    placesStore: PlacesStore,
) {
    var exportRequest by remember { mutableStateOf<GeoVaultShareSaveExportRequest?>(null) }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }

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
                    val baseName = GeoVaultExportFileNames.timestamped("places_export")
                    exportRequest = GeoVaultShareSaveExportRequest(
                        title = "Share places",
                        fileName = "$baseName.kmz",
                        mimeType = GeoVaultStandardFileTypes.MIME_KMZ,
                        bytes = PlacesExporter.buildKmzBytes(places.filter { it in selected }),
                        chooserTitle = "Share places",
                        saveLabel = "Save to device",
                    )
                }
            },
            onDismissRequest = onDismissRequest,
        )
    }

    GeoVaultShareSaveExportHost(
        request = exportRequest,
        onConsumed = { exportRequest = null },
    )

    snackbarMessage?.let { message ->
        GeoVaultAppSnackbarLayer(
            snackbar = GeoVaultSnackbarModel(id = message, message = message),
            onDismissSnackbar = { snackbarMessage = null },
            update = null,
            onDismissUpdate = {},
        )
    }
}
