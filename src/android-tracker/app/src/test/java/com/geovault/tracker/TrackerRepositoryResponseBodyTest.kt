package com.geovault.tracker

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class TrackerRepositoryResponseBodyTest {
    @Test
    fun closeNoBodyResponse_closesResponseBody() {
        val body = CloseTrackingResponseBody("ok")
        val response: Response<ResponseBody> = Response.success(body)

        TrackerRepository.closeNoBodyResponse(response)

        assertTrue(body.closed)
    }

    private class CloseTrackingResponseBody(
        private val payload: String
    ) : ResponseBody() {
        var closed: Boolean = false

        override fun contentType() = "application/json".toMediaType()

        override fun contentLength(): Long = payload.toByteArray().size.toLong()

        override fun source(): BufferedSource = Buffer().writeUtf8(payload)

        override fun close() {
            closed = true
            super.close()
        }
    }
}
