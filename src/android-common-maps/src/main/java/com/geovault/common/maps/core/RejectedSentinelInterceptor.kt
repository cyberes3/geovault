package com.geovault.common.maps.core

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Short-circuits requests to the [MapResourceUrlTransform.REJECTED_HOST]
 * sentinel with a synthetic HTTP 410 (Gone) response.
 *
 * Why a synthetic response instead of letting DNS fail?
 *  - DNS lookup latency for `.invalid` hosts is implementation-dependent
 *    on Android (some resolvers wait for full timeout before NXDOMAIN).
 *  - We want MapLibre's request to **complete with an error immediately**
 *    so the engine can move on without holding any internal state for
 *    the rejected resource.
 *
 * Install at the **front** of the OkHttp interceptor chain so it runs
 * before any auth / origin / failure interceptors that would otherwise
 * waste work on a doomed request.
 */
internal class RejectedSentinelInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!MapResourceUrlTransform.isRejectedSentinel(request.url)) {
            return chain.proceed(request)
        }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(SYNTHETIC_STATUS_CODE)
            .message(SYNTHETIC_STATUS_MESSAGE)
            .body("".toResponseBody(SYNTHETIC_CONTENT_TYPE))
            .build()
    }

    private companion object {
        const val SYNTHETIC_STATUS_CODE = 410
        const val SYNTHETIC_STATUS_MESSAGE = "Gone (vetoed by MapResourceUrlTransform)"
        val SYNTHETIC_CONTENT_TYPE = "text/plain; charset=utf-8".toMediaType()
    }
}
