package com.geovault.common.net

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Minimal TCP/TLS + HTTP check that the configured GeoVault host answers.
 *
 * Uses an **unauthenticated** GET so invalid or expired OAuth tokens do not affect the result.
 * Any HTTP response (including 401/403/500) means the host was reached; only transport failures
 * yield `false`.
 */
object GeoVaultServerTransportProbe {

    private const val TAG = "GeoVaultTransportProbe"
    private const val HEALTH_PATH = "/api/health/"

    fun probe(baseUrl: String, callback: (Boolean) -> Unit) {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isBlank()) {
            Log.w(TAG, "probe: blank server URL")
            callback(false)
            return
        }
        val url = "$trimmed$HEALTH_PATH"
        Log.d(TAG, "probe: GET $url")
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        client.newCall(request).enqueue(
            object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    Log.w(TAG, "probe: transport failure url=$url msg=${e.message}", e)
                    callback(false)
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val code = response.code
                    response.close()
                    Log.d(TAG, "probe: host answered url=$url http=$code")
                    callback(true)
                }
            },
        )
    }
}
