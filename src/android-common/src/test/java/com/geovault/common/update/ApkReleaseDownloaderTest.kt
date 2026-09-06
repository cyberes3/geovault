package com.geovault.common.update

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ApkReleaseDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `download streams body to file`() = runBlocking {
        val body = "abcdefgh"
        val dest = File(tempFolder.root, "out.apk")
        val downloader = ApkReleaseDownloader(client = clientReturning(body.toByteArray()))
        val result = downloader.download(
            url = "https://example.test/apk",
            knownTotalBytes = body.length.toLong(),
            destination = dest,
            onProgress = { },
        )
        assertTrue(result.isSuccess)
        assertArrayEquals(body.toByteArray(Charsets.UTF_8), dest.readBytes())
    }

    @Test
    fun `download rejects http url without writing a file`() = runBlocking {
        val dest = File(tempFolder.root, "http.apk")
        val downloader = ApkReleaseDownloader(client = clientReturning("secret".toByteArray()))
        val result = downloader.download(
            url = "http://example.test/apk",
            knownTotalBytes = 6L,
            destination = dest,
            onProgress = { },
        )
        assertTrue(result.isFailure)
        assertEquals("insecure_download_url", result.exceptionOrNull()?.message)
        assertFalse(dest.exists())
    }

    @Test
    fun `download rejects size mismatch and deletes partial file`() = runBlocking {
        val body = "abcdefgh"
        val dest = File(tempFolder.root, "size.apk")
        val downloader = ApkReleaseDownloader(client = clientReturning(body.toByteArray()))
        val result = downloader.download(
            url = "https://example.test/apk",
            knownTotalBytes = 99L,
            destination = dest,
            onProgress = { },
        )
        assertTrue(result.isFailure)
        assertEquals("apk_size_mismatch", result.exceptionOrNull()?.message)
        assertFalse(dest.exists())
    }

    private fun clientReturning(body: ByteArray): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/vnd.android.package-archive".toMediaType()))
                    .build()
            }
            .build()
}
