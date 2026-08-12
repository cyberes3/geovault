package com.geovault.common.files

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.util.UUID

data class StagedIncomingFile(
    val path: String,
    val displayName: String,
)

/**
 * Copies an incoming content/file URI into app-private cache so later form work does not
 * depend on a temporary grant from [android.content.Intent.ACTION_SEND] / VIEW.
 */
class GeoVaultIncomingFileStager(
    private val cacheDir: File,
    private val metadata: GeoVaultOpenableUriMetadata,
) {
    fun stage(
        contentResolver: ContentResolver,
        uri: Uri,
        fileName: String = metadata.displayName(uri),
    ): StagedIncomingFile {
        val safeName = sanitizeFileName(fileName)
        val dir = File(cacheDir, "$INCOMING_SUBDIR/${UUID.randomUUID()}").apply { mkdirs() }
        val dest = File(dir, safeName)
        val copied = when {
            uri.scheme.equals("file", ignoreCase = true) -> {
                val source = uri.path?.let(::File)
                if (source != null && source.isFile) {
                    source.copyTo(dest, overwrite = true)
                    true
                } else {
                    false
                }
            }
            else -> {
                contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                    true
                } ?: false
            }
        }
        if (!copied || !dest.isFile) {
            dest.parentFile?.deleteRecursively()
            error("Could not read incoming file")
        }
        return StagedIncomingFile(path = dest.absolutePath, displayName = fileName)
    }

    fun delete(staged: StagedIncomingFile) {
        val file = File(staged.path)
        val parent = file.parentFile
        file.delete()
        parent?.delete()
    }

    companion object {
        const val INCOMING_SUBDIR = "incoming"

        fun sanitizeFileName(name: String): String {
            val leaf = name.substringAfterLast('/').substringAfterLast('\\')
            val cleaned = buildString(leaf.length) {
                leaf.forEach { ch ->
                    append(if (ch == '\u0000') '_' else ch)
                }
            }.trim().ifBlank { "incoming" }
            return cleaned
        }
    }
}
