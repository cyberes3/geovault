package com.geovault.common.update

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class ApkReleaseDownloaderTest {

    @get:Rule
    val server = MockWebServer()

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `download streams body to file`() = runBlocking {
        val body = "abcdefgh"
        server.enqueue(
            MockResponse()
                .setBody(body)
                .addHeader("Content-Length", body.length.toString()),
        )
        val dest = File(tempFolder.root, "out.apk")
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        val downloader = ApkReleaseDownloader(client = client)
        val result = downloader.download(
            url = server.url("/apk").toString(),
            knownTotalBytes = body.length.toLong(),
            destination = dest,
            onProgress = { },
        )
        assertTrue(result.isSuccess)
        assertArrayEquals(body.toByteArray(Charsets.UTF_8), dest.readBytes())
    }
}
