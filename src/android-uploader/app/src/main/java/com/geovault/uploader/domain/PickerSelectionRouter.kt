package com.geovault.uploader.domain

import android.net.Uri
import com.geovault.uploader.data.FileMetadataRepository

sealed interface PickerRouteDecision {
    data object NoSelection : PickerRouteDecision
    data class RejectedOnly(val rejectedFileNames: List<String>) : PickerRouteDecision
    data class SingleFile(val uri: Uri, val rejectedFileNames: List<String>) : PickerRouteDecision
    data class MultiFile(val uris: List<Uri>, val rejectedFileNames: List<String>) : PickerRouteDecision
}

class PickerSelectionRouter(
    private val fileMetadataRepository: FileMetadataRepository
) {
    fun decide(uris: List<Uri>, applyExtensionFilter: Boolean): PickerRouteDecision {
        if (uris.isEmpty()) return PickerRouteDecision.NoSelection

        val (supportedUris, rejectedUris) = if (applyExtensionFilter) {
            uris.partition(::isSupportedUri)
        } else {
            uris to emptyList()
        }
        val rejectedNames = rejectedUris.map(fileMetadataRepository::filenameFromUri)

        if (supportedUris.isEmpty()) {
            return if (rejectedNames.isNotEmpty()) {
                PickerRouteDecision.RejectedOnly(rejectedNames)
            } else {
                PickerRouteDecision.NoSelection
            }
        }
        if (supportedUris.size == 1) {
            return PickerRouteDecision.SingleFile(
                uri = supportedUris.first(),
                rejectedFileNames = rejectedNames
            )
        }
        return PickerRouteDecision.MultiFile(
            uris = supportedUris,
            rejectedFileNames = rejectedNames
        )
    }

    private fun isSupportedUri(uri: Uri): Boolean {
        val filename = fileMetadataRepository.filenameFromUri(uri)
        return FilenamePolicy.isSupportedImportType(filename)
    }
}
