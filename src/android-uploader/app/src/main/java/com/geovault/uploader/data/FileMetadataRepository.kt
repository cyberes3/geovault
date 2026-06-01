package com.geovault.uploader.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.geovault.uploader.domain.ImportFilenameResolver
import java.io.File

class FileMetadataRepository(private val contentResolver: ContentResolver) : ImportFilenameResolver {
    override fun filenameFromUri(uri: Uri): String {
        var filename = "uploaded_file"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    filename = cursor.getString(nameIndex) ?: filename
                }
            }
        } catch (_: Exception) {
            val path = uri.path
            if (path != null) filename = File(path).name
        }
        return filename
    }

    fun fileSizeFromUri(uri: Uri): Long {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && cursor.moveToFirst()) {
                    cursor.getLong(sizeIndex).takeIf { it > 0L }?.let { return it }
                }
            }
        } catch (_: Exception) {
            // Fall through to descriptor fallback.
        }
        try {
            contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.statSize.takeIf { it > 0L }?.let { return it }
            }
        } catch (_: Exception) {
            // Size unavailable without reading the full stream.
        }
        return 0L
    }

    fun fileModifiedAtFromUri(uri: Uri): Long? {
        var modifiedAt: Long? = null
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val lastModifiedIndex = cursor.getColumnIndex("last_modified")
                    if (lastModifiedIndex >= 0) {
                        modifiedAt = normalizeEpochMillis(cursor.getLong(lastModifiedIndex))
                    } else {
                        val dateModifiedIndex = cursor.getColumnIndex("date_modified")
                        if (dateModifiedIndex >= 0) {
                            modifiedAt = normalizeEpochMillis(cursor.getLong(dateModifiedIndex))
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Fall through to file-system fallback.
        }
        if (modifiedAt == null) {
            val fileLastModified = uri.path?.let { File(it).lastModified() } ?: 0L
            if (fileLastModified > 0L) {
                modifiedAt = fileLastModified
            }
        }
        return modifiedAt
    }

    private fun normalizeEpochMillis(value: Long): Long? {
        if (value <= 0L) return null
        return if (value < 1_000_000_000_000L) value * 1000L else value
    }
}
