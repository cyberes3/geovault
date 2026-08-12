package com.geovault.common.maps.kml.icon

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

class KmlRemoteIconFetcherTest {

    @get:Rule
    val server = MockWebServer()

    private val fetcher = KmlRemoteIconFetcher(
        client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(2, TimeUnit.SECONDS)
            .build(),
        maxBytes = 16,
    )

    @Test
    fun fetch_returnsBodyWhenUnderCap() {
        val body = byteArrayOf(1, 2, 3, 4)
        server.enqueue(MockResponse().setBody(Buffer().write(body)))
        assertArrayEquals(body, fetcher.fetch(server.url("/icon.png").toString()))
    }

    @Test
    fun fetch_rejectsOversizedBody() {
        server.enqueue(MockResponse().setBody("abcdefghijklmnopqrstuvwxyz"))
        assertNull(fetcher.fetch(server.url("/big.png").toString()))
    }

    @Test
    fun fetch_rejectsHttpError() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(fetcher.fetch(server.url("/missing.png").toString()))
    }

    @Test
    fun fetch_rejectsNonHttpUrl() {
        assertNull(fetcher.fetch("file:///tmp/icon.png"))
    }
}
