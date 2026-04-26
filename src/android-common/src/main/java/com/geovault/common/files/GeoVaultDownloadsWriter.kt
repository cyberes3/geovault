package com.geovault.common.files

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Writes a byte payload to the device's public Downloads collection via [MediaStore.Downloads].
 *
 * Targets Android 14+ (`minSdk = 34`). MediaStore is the only supported public-Downloads write
 * surface on modern Android; the legacy `getExternalStoragePublicDirectory(...)` File path requires
 * scoped-storage opt-out and is intentionally not provided here.
 *
 * On any failure after the row is inserted, the row is deleted so MediaStore is not left holding an
 * orphan zero-byte entry. Returns a [Result] so callers map to UI states without `try/catch`.
 *
 * **Threading**: All blocking I/O runs on [Dispatchers.IO]; callers may invoke from any context.
 */
class GeoVaultDownloadsWriter(context: Context) {

    private val appContext: Context = context.applicationContext

    suspend fun write(
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
    ): Result<Uri> = withContext(Dispatchers.IO) {
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require(mimeType.isNotBlank()) { "mimeType must not be blank" }

        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return@withContext Result.failure(
                IllegalStateException("MediaStore.insert returned null for $displayName"),
            )

        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("openOutputStream returned null for $uri")

            val publish = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, publish, null, null)

            Result.success(uri)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            Result.failure(t)
        }
    }
}
