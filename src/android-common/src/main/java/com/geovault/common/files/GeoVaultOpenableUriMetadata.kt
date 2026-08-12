package com.geovault.common.files

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Reads [OpenableColumns] (and small filesystem fallbacks) from a content or file URI.
 */
class GeoVaultOpenableUriMetadata(
    private val contentResolver: ContentResolver,
) {
    fun displayName(uri: Uri): String {
        return displayNameOrNull(uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "Unknown file"
    }

    fun displayNameOrNull(uri: Uri): String? {
        if (uri.scheme.equals("content", ignoreCase = true)) {
            queryColumnString(uri, OpenableColumns.DISPLAY_NAME)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }
        if (uri.scheme.equals("file", ignoreCase = true)) {
            uri.path?.let { File(it).name }?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    fun sizeBytes(uri: Uri): Long {
        queryColumnLong(uri, OpenableColumns.SIZE)?.takeIf { it > 0L }?.let { return it }
        try {
            contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.statSize.takeIf { it > 0L }?.let { return it }
            }
        } catch (_: Exception) {
            // Size unavailable without reading the full stream.
        }
        return 0L
    }

    fun lastModifiedMillis(uri: Uri): Long? {
        queryColumnLong(uri, "last_modified")?.let { return normalizeEpochMillis(it) }
        queryColumnLong(uri, "date_modified")?.let { return normalizeEpochMillis(it) }
        val fileLastModified = uri.path?.let { File(it).lastModified() } ?: 0L
        if (fileLastModified > 0L) return fileLastModified
        return null
    }

    private fun queryColumnString(uri: Uri, column: String): String? {
        return try {
            contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(column)
                if (index < 0) null else cursor.getString(index)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun queryColumnLong(uri: Uri, column: String): Long? {
        return try {
            contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(column)
                if (index < 0) null else cursor.getLong(index)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeEpochMillis(value: Long): Long? {
        if (value <= 0L) return null
        return if (value < 1_000_000_000_000L) value * 1000L else value
    }
}
