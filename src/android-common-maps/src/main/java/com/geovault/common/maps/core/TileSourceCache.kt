package com.geovault.common.maps.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.RetrofitClient
import com.geovault.common.maps.model.MapConfigError
import com.geovault.common.maps.model.SOURCE_MAPTILER_HYBRID
import com.geovault.common.maps.model.SOURCE_MAPTILER_STREETS
import com.geovault.common.maps.model.SOURCE_MAPTILER_TOPO
import com.geovault.common.maps.model.TileSource
import com.geovault.common.maps.model.TileSourceResponse
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

internal object TileSourceCache {
    private const val TAG = "TileSourceCache"

    @Volatile
    private var cachedResult: TileSourceFetchResult? = null

    @Volatile
    private var cachedServerUrl: String? = null

    @Volatile
    private var fetchInProgress = false

    private val lock = Any()
    private val pendingCallbacks = mutableListOf<(TileSourceFetchResult) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun getTileSources(context: Context, onResult: (TileSourceFetchResult) -> Unit) {
        val serverUrl = GeovaultAuthManager.getServerUrl(context).trimEnd('/')
        if (serverUrl.isEmpty()) {
            mainHandler.post {
                onResult(
                    TileSourceFetchResult.ConfigurationError(
                        "A GeoVault server must be configured before maps can load.",
                    ),
                )
            }
            return
        }

        if (cachedResult != null && cachedServerUrl == serverUrl) {
            mainHandler.post { onResult(requireNotNull(cachedResult)) }
            return
        }

        if (cachedServerUrl != null && cachedServerUrl != serverUrl) {
            cachedResult = null
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
                val body = response.body()
                if (!response.isSuccessful) {
                    Log.e(
                        TAG,
                        "Tile source fetch failed: code=${response.code()} message=${response.message()} " +
                            "url=${call.request().url}",
                    )
                } else if (body == null) {
                    Log.e(TAG, "Tile source fetch returned an empty body: url=${call.request().url}")
                }
                val result = when {
                    !response.isSuccessful -> TileSourceFetchResult.TransientFailure(
                        "Could not load map sources from the GeoVault server (HTTP ${response.code()}).",
                    )
                    body == null -> TileSourceFetchResult.TransientFailure(
                        "Could not load map sources from the GeoVault server.",
                    )
                    else -> body.toTileSourceFetchResult()
                }
                val callbacks: List<(TileSourceFetchResult) -> Unit>
                synchronized(lock) {
                    if (result.isCacheable()) {
                        cachedResult = result
                        cachedServerUrl = serverUrl
                    }
                    fetchInProgress = false
                    callbacks = pendingCallbacks.toList()
                    pendingCallbacks.clear()
                }
                mainHandler.post { callbacks.forEach { it(result) } }
            }

            override fun onFailure(call: Call<TileSourceResponse>, t: Throwable) {
                Log.e(TAG, "Tile source fetch threw: url=${call.request().url}", t)
                val callbacks: List<(TileSourceFetchResult) -> Unit>
                val result = TileSourceFetchResult.TransientFailure(
                    "Could not load map sources from the GeoVault server. Check your connection and try again.",
                )
                synchronized(lock) {
                    fetchInProgress = false
                    callbacks = pendingCallbacks.toList()
                    pendingCallbacks.clear()
                }
                mainHandler.post { callbacks.forEach { it(result) } }
            }
        })
    }

    fun invalidate() {
        synchronized(lock) {
            cachedResult = null
            cachedServerUrl = null
        }
    }

}

internal sealed class TileSourceFetchResult {
    data class Success(val sources: List<TileSource>) : TileSourceFetchResult()
    data class ConfigurationError(val message: String) : TileSourceFetchResult()
    data class TransientFailure(val message: String) : TileSourceFetchResult()
}

internal fun TileSourceFetchResult.isCacheable(): Boolean = this is TileSourceFetchResult.Success

internal fun TileSourceResponse.toTileSourceFetchResult(): TileSourceFetchResult {
    val configurationMessages = map_config_errors.map { it.displayMessage() } + missingExpectedMapMessage()
    return if (configurationMessages.isNotEmpty()) {
        TileSourceFetchResult.ConfigurationError(
            configurationMessages.joinToString(separator = "\n"),
        )
    } else {
        TileSourceFetchResult.Success(sources)
    }
}

private fun TileSourceResponse.missingExpectedMapMessage(): List<String> {
    val mapLibreSourceIds = sources
        .filter { !it.client_config.style_url.isNullOrBlank() }
        .map { it.id }
        .toSet()
    val missing = EXPECTED_MAPLIBRE_SOURCES.filter { it.id !in mapLibreSourceIds }
    if (missing.isEmpty()) return emptyList()
    return listOf(
        "Map setup is missing required basemaps: " +
            missing.joinToString { "${it.label} (${it.id})" } +
            ". Code: required_maplibre_basemaps_missing.",
    )
}

private fun MapConfigError.displayMessage(): String {
    return message.ifBlank { code.ifBlank { "GeoVault server map configuration is incomplete." } }
}

private data class ExpectedMapLibreSource(
    val id: String,
    val label: String,
)

private val EXPECTED_MAPLIBRE_SOURCES = listOf(
    ExpectedMapLibreSource(SOURCE_MAPTILER_STREETS, "Streets"),
    ExpectedMapLibreSource(SOURCE_MAPTILER_HYBRID, "Satellite hybrid"),
    ExpectedMapLibreSource(SOURCE_MAPTILER_TOPO, "Topographic"),
)
