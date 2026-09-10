package com.geovault.common.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class GeoVaultApiFailureTest {
    @Test
    fun userMessage_usesServerPrefixWhenHttpCodePresent() {
        val failure = GeoVaultApiFailure(httpCode = 500, serverMessage = "boom")
        assertEquals("Server Error: boom", failure.userMessage())
    }

    @Test
    fun userMessage_fallsBackToHttpCodeWhenServerMessageBlank() {
        val failure = GeoVaultApiFailure(httpCode = 404, serverMessage = "  ")
        assertEquals("Server Error: HTTP 404", failure.userMessage())
    }

    @Test
    fun userMessage_usesNetworkPrefixWhenHttpCodeMissing() {
        val failure = GeoVaultApiFailure(httpCode = null, serverMessage = "timeout")
        assertEquals("Network failed: timeout", failure.userMessage())
    }

    @Test
    fun fromOkHttp_readsBodyAndParsesEnvelopeWhenBodyOmitted() {
        val response = Response.Builder()
            .request(Request.Builder().url("https://example.test/x").build())
            .protocol(Protocol.HTTP_1_1)
            .code(400)
            .message("Bad Request")
            .body("""{"error":"Name is required","code":400}""".toResponseBody("application/json".toMediaType()))
            .build()
        val failure = GeoVaultApiFailure.fromOkHttp(response)
        assertEquals(400, failure.httpCode)
        assertEquals("Name is required", failure.serverMessage)
    }

    @Test
    fun fromOkHttp_prefersExplicitBodyOverResponseBody() {
        val response = Response.Builder()
            .request(Request.Builder().url("https://example.test/x").build())
            .protocol(Protocol.HTTP_1_1)
            .code(404)
            .message("Not Found")
            .body("""{"error":"from body","code":404}""".toResponseBody("application/json".toMediaType()))
            .build()
        val failure = GeoVaultApiFailure.fromOkHttp(response, body = """{"error":"explicit","code":404}""")
        assertEquals("explicit", failure.serverMessage)
    }
}
