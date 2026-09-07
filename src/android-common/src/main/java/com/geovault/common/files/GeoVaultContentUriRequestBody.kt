package com.geovault.common.files

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source

/**
 * Streams a content or file [Uri] into an OkHttp multipart part without buffering the whole file.
 */
class GeoVaultContentUriRequestBody(
    private val contentResolver: ContentResolver,
    private val uri: Uri,
    private val contentType: MediaType?,
    private val metadata: GeoVaultOpenableUriMetadata = GeoVaultOpenableUriMetadata(contentResolver),
) : RequestBody() {
    override fun contentType(): MediaType? = contentType

    override fun contentLength(): Long {
        val size = metadata.sizeBytes(uri)
        return if (size > 0L) size else -1L
    }

    override fun writeTo(sink: BufferedSink) {
        val input = contentResolver.openInputStream(uri)
            ?: throw IOException("Could not read file")
        input.use { stream ->
            sink.writeAll(stream.source())
        }
    }
}
