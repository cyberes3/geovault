package com.geovault.common.map

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.RetrofitClient
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * Single entry point for fetching tile sources from the server.
 * Caches the result per server URL and coalesces in-flight requests so only one
 * GET /api/tiles/sources/ runs at a time. Cache is invalidated when the server URL changes.
 * Callbacks are invoked on the main thread.
 */
object TileSourceCache {

    @Volatile
    private var cachedSources: List<TileSource>? = null

    @Volatile
    private var cachedServerUrl: String? = null

    @Volatile
    private var fetchInProgress = false

    private val lock = Any()
    private val pendingCallbacks = mutableListOf<(List<TileSource>?) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Fetches tile sources from the server (or returns cached if valid).
     * [onResult] is invoked on the main thread with the list, or null if no server or fetch failed.
     */
    fun getTileSources(context: Context, onResult: (List<TileSource>?) -> Unit) {
        val serverUrl = GeovaultAuthManager.getServerUrl(context).trimEnd('/')
        if (serverUrl.isEmpty()) {
            mainHandler.post { onResult(null) }
            return
        }

        if (cachedSources != null && cachedServerUrl == serverUrl) {
            mainHandler.post { onResult(cachedSources) }
            return
        }

        if (cachedServerUrl != null && cachedServerUrl != serverUrl) {
            cachedSources = null
            cachedServerUrl = null
        }

        synchronized(lock) {
            if (fetchInProgress) {
                pendingCallbacks.add(onResult)
                return
            }
            fetchInProgress = true
            pendingCallbacks.add(onResult)
        }

        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(context, baseUrl).create(MapApi::class.java)
        api.getTileSources().enqueue(object : Callback<TileSourceResponse> {
            override fun onResponse(call: Call<TileSourceResponse>, response: Response<TileSourceResponse>) {
                val list = response.body()?.sources
                val callbacks: List<(List<TileSource>?) -> Unit>
                synchronized(lock) {
                    cachedSources = list
                    cachedServerUrl = serverUrl
                    fetchInProgress = false
                    callbacks = pendingCallbacks.toList()
                    pendingCallbacks.clear()
                }
                mainHandler.post { callbacks.forEach { it(cachedSources) } }
            }

            override fun onFailure(call: Call<TileSourceResponse>, t: Throwable) {
                val callbacks: List<(List<TileSource>?) -> Unit>
                synchronized(lock) {
                    fetchInProgress = false
                    callbacks = pendingCallbacks.toList()
                    pendingCallbacks.clear()
                }
                mainHandler.post { callbacks.forEach { it(null) } }
            }
        })
    }
}
