package com.geovault.common.files

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.OutputStream

/**
 * The only SAF document write path. Streams from a staged payload file; never requires the
 * original [ByteArray] to still be in memory.
 */
open class GeoVaultSafDocumentWriter {
    open fun write(resolver: ContentResolver, uri: Uri, payload: File) {
        require(payload.isFile) { "Staged payload is missing: $payload" }
        resolver.openOutputStream(uri, "wt")?.use { output ->
            copyPayload(payload, output)
        } ?: error("Could not open destination for writing")
    }

    fun copyPayload(payload: File, output: OutputStream) {
        payload.inputStream().use { input ->
            input.copyTo(output)
        }
        output.flush()
    }

    open fun deleteDocument(resolver: ContentResolver, uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }
    }
}
