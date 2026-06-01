package com.geovault.uploader.domain

import android.net.Uri
sealed interface PickerRouteDecision {
    data object NoSelection : PickerRouteDecision
    data class RejectedOnly(val rejectedFileNames: List<String>) : PickerRouteDecision
    data class SupportedSelection(
        val uris: List<Uri>,
        val rejectedFileNames: List<String>,
    ) : PickerRouteDecision
}

class PickerSelectionRouter(
    private val filenameResolver: ImportFilenameResolver,
) {
    fun decide(uris: List<Uri>, applyExtensionFilter: Boolean): PickerRouteDecision {
        if (uris.isEmpty()) return PickerRouteDecision.NoSelection

        val (supportedUris, rejectedUris) = if (applyExtensionFilter) {
            uris.partition(::isSupportedUri)
        } else {
            uris to emptyList()
        }
        val rejectedNames = rejectedUris.map(filenameResolver::filenameFromUri)

        if (supportedUris.isEmpty()) {
            return if (rejectedNames.isNotEmpty()) {
                PickerRouteDecision.RejectedOnly(rejectedNames)
            } else {
                PickerRouteDecision.NoSelection
            }
        }
        return PickerRouteDecision.SupportedSelection(
            uris = supportedUris,
            rejectedFileNames = rejectedNames,
        )
    }

    private fun isSupportedUri(uri: Uri): Boolean {
        val filename = filenameResolver.filenameFromUri(uri)
        return FilenamePolicy.isSupportedImportType(filename)
    }
}
