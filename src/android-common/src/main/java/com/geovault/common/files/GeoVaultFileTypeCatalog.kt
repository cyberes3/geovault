package com.geovault.common.files

import android.net.Uri
import java.util.Locale

/**
 * Immutable lookup of supported extensions and MIME types. Apps own their catalogs; this class
 * is the shared matching and classification behavior.
 */
class GeoVaultFileTypeCatalog(types: List<GeoVaultFileType>) {
    val types: List<GeoVaultFileType> = types.toList()

    val extensions: Set<String> = types.map { it.extension }.toSet()

    /**
     * Distinct MIME types in first-seen order, suitable for [android.content.Intent.EXTRA_MIME_TYPES]
     * and SAF pickers.
     */
    val mimeTypes: Array<String> = LinkedHashSet<String>().apply {
        types.forEach { addAll(it.mimeTypes) }
    }.toTypedArray()

    init {
        require(types.isNotEmpty()) { "catalog must contain at least one file type" }
        val duplicate = types.groupingBy { it.extension }.eachCount().filterValues { it > 1 }
        require(duplicate.isEmpty()) {
            "duplicate extensions in catalog: ${duplicate.keys.joinToString()}"
        }
    }

    fun isSupportedFilename(filename: String): Boolean {
        val extension = extensionFor(filename) ?: return false
        return extension in extensions
    }

    fun extensionFor(filename: String): String? {
        val trimmed = filename.trim()
        val dot = trimmed.lastIndexOf('.')
        if (dot <= 0 || dot == trimmed.lastIndex) return null
        return trimmed.substring(dot + 1).lowercase(Locale.US).takeIf { it.isNotEmpty() }
    }

    fun primaryMimeType(extension: String): String? {
        val lower = extension.lowercase(Locale.US).removePrefix(".")
        return types.firstOrNull { it.extension == lower }?.mimeTypes?.first()
    }

    fun stripSupportedExtension(filename: String): String {
        val trimmed = filename.trim()
        val extension = extensionFor(trimmed) ?: return filename
        if (extension !in extensions) return filename
        return trimmed.substring(0, trimmed.length - extension.length - 1)
    }

    fun typeForMime(mimeType: String): GeoVaultFileType? {
        val normalized = mimeType.lowercase(Locale.US).substringBefore(';').trim()
        if (normalized.isEmpty() || normalized == "*/*" || normalized.endsWith("/*")) {
            return null
        }
        return types.firstOrNull { normalized in it.mimeTypes }
    }

    /**
     * Display name with a catalog extension, using [mimeType] when the name itself is not
     * a supported filename (common for Downloads `msf:` URIs).
     */
    fun preferredFileName(displayName: String, mimeType: String?): String {
        if (isSupportedFilename(displayName)) return displayName
        val ext = mimeType?.let { typeForMime(it)?.extension } ?: return displayName
        val trimmed = displayName.trim().ifBlank { "incoming" }
        return "$trimmed.$ext"
    }

    fun classify(
        uris: List<Uri>,
        displayNameOf: (Uri) -> String,
    ): GeoVaultIncomingFileClassification = classify(uris, displayNameOf) { null }

    fun classify(
        uris: List<Uri>,
        displayNameOf: (Uri) -> String,
        mimeTypeOf: (Uri) -> String?,
    ): GeoVaultIncomingFileClassification {
        val supported = ArrayList<Uri>()
        val rejected = ArrayList<String>()
        for (uri in uris) {
            val name = displayNameOf(uri)
            val mime = mimeTypeOf(uri)
            if (isSupportedFilename(name) || (mime != null && typeForMime(mime) != null)) {
                supported.add(uri)
            } else {
                rejected.add(name)
            }
        }
        return GeoVaultIncomingFileClassification(
            supported = supported,
            rejectedFileNames = rejected,
        )
    }
}
