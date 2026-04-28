package com.geovault.common.maps.core

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RejectedSentinelInterceptorTest {

    private val interceptor = RejectedSentinelInterceptor()

    @Test
    fun rejectedSentinelHost_shortCircuitsWith410() {
        val request = Request.Builder()
            .url("https://${MapResourceUrlTransform.REJECTED_HOST}/source")
            .build()
        val chain = StubChain(request)
        val response = interceptor.intercept(chain)
        assertEquals(410, response.code)
        assertEquals(0, chain.proceedCount)
    }

    @Test
    fun nonSentinelHost_passesThrough() {
        val request = Request.Builder().url("https://tile.openstreetmap.org/0/0/0.png").build()
        val proxied = stubResponse(request, 200)
        val chain = StubChain(request, proxied)
        val response = interceptor.intercept(chain)
        assertSame(proxied, response)
        assertEquals(1, chain.proceedCount)
    }

    private class StubChain(
        private val request: Request,
        private val proxiedResponse: Response? = null,
    ) : Interceptor.Chain {
        var proceedCount = 0
            private set

        override fun request(): Request = request

        override fun proceed(request: Request): Response {
            proceedCount++
            return proxiedResponse ?: stubResponse(request, 200)
        }

        override fun call(): okhttp3.Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis(): Int = 0
        override fun connection(): okhttp3.Connection? = null
        override fun readTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        override fun writeTimeoutMillis(): Int = 0
    }

    companion object {
        fun stubResponse(request: Request, code: Int): Response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("ok")
            .body("".toResponseBody("text/plain".toMediaType()))
            .build()
    }
}
