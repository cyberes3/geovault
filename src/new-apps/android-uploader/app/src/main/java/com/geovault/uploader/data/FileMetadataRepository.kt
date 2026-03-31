package com.geovault.uploader.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

class FileMetadataRepository(private val contentResolver: ContentResolver) {
    fun filenameFromUri(uri: Uri): String {
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
        var size = 0L
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && cursor.moveToFirst()) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        } catch (_: Exception) {
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    size = stream.available().toLong()
                }
            } catch (_: Exception) {
                // Keep zero.
            }
        }
        return size
    }
}
