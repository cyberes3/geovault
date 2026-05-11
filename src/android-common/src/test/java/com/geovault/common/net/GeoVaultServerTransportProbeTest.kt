package com.geovault.common.net

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeoVaultServerTransportProbeTest {

    @Test
    fun probe_blankUrl_reportsUnreachableImmediately() {
        val latch = CountDownLatch(1)
        var result = true
        GeoVaultServerTransportProbe.probe("   ") {
            result = it
            latch.countDown()
        }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertFalse(result)
    }

    @Test
    fun probe_anyHttpResponseIsReachable_including401() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"Unauthorized\"}"))
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            val latch = CountDownLatch(1)
            var reachable = false
            GeoVaultServerTransportProbe.probe(base) {
                reachable = it
                latch.countDown()
            }
            assertTrue(latch.await(10, TimeUnit.SECONDS))
            assertTrue(reachable)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun probe_http500StillReachable() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500))
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            val latch = CountDownLatch(1)
            var reachable = false
            GeoVaultServerTransportProbe.probe(base) {
                reachable = it
                latch.countDown()
            }
            assertTrue(latch.await(10, TimeUnit.SECONDS))
            assertTrue(reachable)
        } finally {
            server.shutdown()
        }
    }
}
