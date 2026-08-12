package com.geovault.uploader.domain

import android.net.Uri
import com.geovault.common.files.GeoVaultUploadFileTypes

sealed interface PickerRouteDecision {
    data object NoSelection : PickerRouteDecision
    data class RejectedOnly(val rejectedFileNames: List<String>) : PickerRouteDecision
    data class SupportedSelection(
        val uris: List<Uri>,
        val rejectedFileNames: List<String>,
    ) : PickerRouteDecision
}

class PickerSelectionRouter(
    private val displayNameOf: (Uri) -> String,
) {
    fun decide(uris: List<Uri>, applyExtensionFilter: Boolean): PickerRouteDecision {
        if (uris.isEmpty()) return PickerRouteDecision.NoSelection
        if (!applyExtensionFilter) {
            return PickerRouteDecision.SupportedSelection(uris, emptyList())
        }
        val classification = GeoVaultUploadFileTypes.catalog.classify(uris, displayNameOf)
        if (classification.supported.isEmpty()) {
            return if (classification.rejectedFileNames.isNotEmpty()) {
                PickerRouteDecision.RejectedOnly(classification.rejectedFileNames)
            } else {
                PickerRouteDecision.NoSelection
            }
        }
        return PickerRouteDecision.SupportedSelection(
            uris = classification.supported,
            rejectedFileNames = classification.rejectedFileNames,
        )
    }
}
