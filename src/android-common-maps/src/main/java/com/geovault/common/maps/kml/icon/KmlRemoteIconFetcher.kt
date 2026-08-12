package com.geovault.common.maps.kml.icon

import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Bounded HTTP GET for KML icon images. HTTP/HTTPS only; bodies larger than [maxBytes] are rejected.
 */
class KmlRemoteIconFetcher(
    private val client: OkHttpClient = defaultClient(),
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
) : KmlIconBytesFetcher {

    override fun fetch(url: String): ByteArray? {
        val requestUrl = url.toHttpUrlOrNull() ?: return null
        if (requestUrl.scheme != "http" && requestUrl.scheme != "https") return null
        val request = Request.Builder().url(requestUrl).get().build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body
                val declared = body.contentLength()
                if (declared > maxBytes) return null
                body.byteStream().use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(8 * 1024)
                    var total = 0
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) return null
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch KML icon: $url", e)
            null
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES: Int = 1 * 1024 * 1024
        private const val TAG = "KmlRemoteIconFetcher"
        private const val TIMEOUT_SECONDS = 5L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
