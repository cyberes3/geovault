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
import com.geovault.common.maps.model.SOURCE_MAPTILER_STREETS_DARK
import com.geovault.common.maps.model.SOURCE_MAPTILER_TOPO
import com.geovault.common.maps.model.TileSource
import com.geovault.common.maps.model.TileSourceResponse
import com.geovault.common.net.GeoVaultValidatedInternet
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

internal object TileSourceCache {
    private const val TAG = "TileSourceCache"
    private const val RETRY_DELAY_MS = 5_000L

    @Volatile
    private var cachedResult: TileSourceFetchResult? = null

    @Volatile
    private var cachedServerUrl: String? = null

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var session: LoadSession? = null

    fun getTileSources(context: Context, onResult: (TileSourceFetchResult) -> Unit) {
        val appContext = context.applicationContext
        val serverUrl = GeovaultAuthManager.getServerUrl(appContext).trimEnd('/')
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

        synchronized(lock) {
            if (cachedServerUrl != null && cachedServerUrl != serverUrl) {
                cachedResult = null
                cachedServerUrl = null
            }
            val memory = cachedResult
            if (memory != null && cachedServerUrl == serverUrl) {
                mainHandler.post {
                    applyConnectivityForCachedResult(appContext, memory)
                    onResult(memory)
                }
                return
            }
            val existing = session
            if (existing != null && existing.serverUrl == serverUrl) {
                existing.listeners.add(onResult)
                return
            }
        }

        val diskSuccess = usableCachedTileSources(appContext, serverUrl)
        val plan = MapNetworkAccessPolicy.plan(
            hasValidatedInternet = GeoVaultValidatedInternet.isAvailable(appContext),
            hasCache = diskSuccess != null,
        )
        val cacheForFirstPaint = diskSuccess?.copy(forcedCacheOnly = plan != MapNetworkAccessPlan.WaitForNetwork)
        val gate = MapMetadataLoadGate(
            plan = plan,
            cached = cacheForFirstPaint,
            timeoutPlaceholder = TILE_SOURCES_WAIT_PLACEHOLDER,
        )
        val newSession = LoadSession(
            serverUrl = serverUrl,
            plan = plan,
            gate = gate,
            retryOnFailure = plan == MapNetworkAccessPlan.WaitForNetwork,
        )
        newSession.listeners.add(onResult)

        synchronized(lock) {
            session?.let { cancelDeadline(it) }
            session = newSession
        }

        gate.immediateDelivery()?.let { delivery ->
            deliver(appContext, newSession, delivery, fromNetwork = false, deadlineExpired = false)
            enqueueFetch(appContext, newSession)
            return
        }

        mainHandler.post { applyConnectivity(appContext, plan, deadlineExpired = false) }
        enqueueFetch(appContext, newSession)
        val deadlineMs = MapNetworkAccessPolicy.firstPaintDeadlineMs(plan)
        if (deadlineMs > 0L) {
            val runnable = Runnable {
                val delivery = newSession.gate.onDeadline() ?: return@Runnable
                deliver(appContext, newSession, delivery, fromNetwork = false, deadlineExpired = true)
            }
            newSession.deadlineRunnable = runnable
            mainHandler.postDelayed(runnable, deadlineMs)
        }
    }

    fun invalidate() {
        synchronized(lock) {
            cachedResult = null
            cachedServerUrl = null
            session?.let { cancelDeadline(it) }
            session = null
        }
    }

    private fun enqueueFetch(context: Context, load: LoadSession) {
        val baseUrl = if (load.serverUrl.endsWith("/")) load.serverUrl else "${load.serverUrl}/"
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
                    ).withCachedFallback(context, load.serverUrl)
                    body == null -> TileSourceFetchResult.TransientFailure(
                        "Could not load map sources from the GeoVault server.",
                    ).withCachedFallback(context, load.serverUrl)
                    else -> {
                        val parsed = body.toTileSourceFetchResult()
                        if (parsed.isCacheable()) {
                            MapMetadataTempCache.writeTileSources(context, load.serverUrl, body)
                        }
                        parsed
                    }
                }
                handleNetworkResult(context, load, result)
            }

            override fun onFailure(call: Call<TileSourceResponse>, t: Throwable) {
                Log.e(TAG, "Tile source fetch threw: url=${call.request().url}", t)
                val result = TileSourceFetchResult.TransientFailure(
                    "Could not load map sources from the GeoVault server. Check your connection and try again.",
                ).withCachedFallback(context, load.serverUrl)
                handleNetworkResult(context, load, result)
            }
        })
    }

    private fun handleNetworkResult(
        context: Context,
        load: LoadSession,
        result: TileSourceFetchResult,
    ) {
        if (!isCurrentSession(load)) return
        val isUsable = result is TileSourceFetchResult.Success ||
            result is TileSourceFetchResult.ConfigurationError
        val applyLate = load.plan == MapNetworkAccessPlan.WaitForNetwork && isUsable
        val delivery = load.gate.onNetworkResult(result, isUsable = isUsable, applyLate = applyLate)
        if (delivery != null) {
            deliver(context, load, delivery, fromNetwork = true, deadlineExpired = false)
        }
        if (result is TileSourceFetchResult.Success || result is TileSourceFetchResult.ConfigurationError) {
            finishSession(load)
            return
        }
        if (load.retryOnFailure) {
            mainHandler.postDelayed({
                if (!isCurrentSession(load)) return@postDelayed
                enqueueFetch(context, load)
            }, RETRY_DELAY_MS)
            return
        }
        if (load.plan != MapNetworkAccessPlan.NetworkWithCacheDeadline) {
            finishSession(load)
        }
    }

    private fun deliver(
        context: Context,
        load: LoadSession,
        delivery: MapMetadataDelivery<TileSourceFetchResult>,
        fromNetwork: Boolean,
        deadlineExpired: Boolean,
    ) {
        val delivered = delivery.value
        val result = if (
            !fromNetwork &&
            delivered is TileSourceFetchResult.Success &&
            (load.plan == MapNetworkAccessPlan.CacheOnly || deadlineExpired)
        ) {
            delivered.copy(forcedCacheOnly = true)
        } else {
            delivered
        }
        if (result.isCacheable()) {
            synchronized(lock) {
                cachedResult = result
                cachedServerUrl = load.serverUrl
            }
        }
        if (!delivery.applyToMap) return
        cancelDeadline(load)
        val callbacks = synchronized(lock) { load.listeners.toList() }
        val cacheOnly = load.plan == MapNetworkAccessPlan.CacheOnly ||
            (load.plan == MapNetworkAccessPlan.NetworkWithCacheDeadline && deadlineExpired && !fromNetwork)
        mainHandler.post {
            MapLibreEngineConnectivity.apply(
                context,
                if (cacheOnly) MapLibreConnectivityMode.CacheOnly else MapLibreConnectivityMode.FollowSystem,
            )
            callbacks.forEach { it(result) }
            if (deadlineExpired && load.plan == MapNetworkAccessPlan.NetworkWithCacheDeadline) {
                finishSession(load)
            }
        }
    }

    private fun applyConnectivityForCachedResult(context: Context, result: TileSourceFetchResult) {
        val cacheOnly = result is TileSourceFetchResult.Success &&
            !GeoVaultValidatedInternet.isAvailable(context)
        MapLibreEngineConnectivity.apply(
            context,
            if (cacheOnly) MapLibreConnectivityMode.CacheOnly else MapLibreConnectivityMode.FollowSystem,
        )
    }

    private fun applyConnectivity(
        context: Context,
        plan: MapNetworkAccessPlan,
        deadlineExpired: Boolean,
    ) {
        val cacheOnly = plan == MapNetworkAccessPlan.CacheOnly ||
            (plan == MapNetworkAccessPlan.NetworkWithCacheDeadline && deadlineExpired)
        MapLibreEngineConnectivity.apply(
            context,
            if (cacheOnly) MapLibreConnectivityMode.CacheOnly else MapLibreConnectivityMode.FollowSystem,
        )
    }

    private fun isCurrentSession(load: LoadSession): Boolean = synchronized(lock) { session === load }

    private fun cancelDeadline(load: LoadSession) {
        load.deadlineRunnable?.let { mainHandler.removeCallbacks(it) }
        load.deadlineRunnable = null
    }

    private fun finishSession(load: LoadSession) {
        synchronized(lock) {
            if (session !== load) return
            cancelDeadline(load)
            session = null
        }
    }

    private class LoadSession(
        val serverUrl: String,
        val plan: MapNetworkAccessPlan,
        val gate: MapMetadataLoadGate<TileSourceFetchResult>,
        val retryOnFailure: Boolean,
        val listeners: MutableList<(TileSourceFetchResult) -> Unit> = mutableListOf(),
    ) {
        var deadlineRunnable: Runnable? = null
    }
}

internal sealed class TileSourceFetchResult {
    data class Success(
        val sources: List<TileSource>,
        val isStale: Boolean = false,
        val fallbackMessage: String? = null,
        val forcedCacheOnly: Boolean = false,
    ) : TileSourceFetchResult()
    data class ConfigurationError(val message: String) : TileSourceFetchResult()
    data class TransientFailure(val message: String) : TileSourceFetchResult()
}

internal fun TileSourceFetchResult.isCacheable(): Boolean =
    this is TileSourceFetchResult.Success && !isStale

internal fun usableCachedTileSources(context: Context, serverUrl: String): TileSourceFetchResult.Success? {
    val cached = MapMetadataTempCache.readTileSources(context, serverUrl) ?: return null
    return cached.toTileSourceFetchResult() as? TileSourceFetchResult.Success
}

internal val TILE_SOURCES_WAIT_PLACEHOLDER = TileSourceFetchResult.TransientFailure(
    "Could not load map sources from the GeoVault server. Check your connection and try again.",
)

internal fun TileSourceFetchResult.TransientFailure.withCachedFallback(
    context: Context,
    serverUrl: String,
): TileSourceFetchResult {
    val cached = MapMetadataTempCache.readTileSources(context, serverUrl)
        ?: return this
    return when (val cachedResult = cached.toTileSourceFetchResult()) {
        is TileSourceFetchResult.Success -> cachedResult.copy(
            isStale = true,
            fallbackMessage = message,
        )
        else -> this
    }
}

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
    ExpectedMapLibreSource(SOURCE_MAPTILER_STREETS_DARK, "Dark streets"),
    ExpectedMapLibreSource(SOURCE_MAPTILER_HYBRID, "Satellite hybrid"),
    ExpectedMapLibreSource(SOURCE_MAPTILER_TOPO, "Topographic"),
)
