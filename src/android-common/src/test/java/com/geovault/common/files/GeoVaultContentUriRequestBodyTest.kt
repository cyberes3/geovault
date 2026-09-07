package com.geovault.common.files

import android.net.Uri
import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class GeoVaultContentUriRequestBodyTest {

    @Test
    fun `writes file bytes and reports length`() {
        val app = RuntimeEnvironment.getApplication()
        val source = File.createTempFile("body", ".kml").apply { writeText("<kml/>") }
        val body = GeoVaultContentUriRequestBody(
            contentResolver = app.contentResolver,
            uri = Uri.fromFile(source),
            contentType = "application/octet-stream".toMediaType(),
        )
        assertEquals(source.length(), body.contentLength())
        val sink = Buffer()
        body.writeTo(sink)
        assertEquals("<kml/>", sink.readUtf8())
        assertTrue(body.contentType().toString().startsWith("application/octet-stream"))
    }
}
