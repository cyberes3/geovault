package com.geovault.common.maps.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.RetrofitClient
import com.geovault.common.maps.model.TileSource
import com.geovault.common.maps.model.TileSourceResponse
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

internal object TileSourceCache {
    @Volatile
    private var cachedSources: List<TileSource>? = null

    @Volatile
    private var cachedServerUrl: String? = null

    @Volatile
    private var fetchInProgress = false

    private val lock = Any()
    private val pendingCallbacks = mutableListOf<(List<TileSource>?) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

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
        val api = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(RetrofitClient.getAuthenticatedOkHttpClient(context))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MapApi::class.java)

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
