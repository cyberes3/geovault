package com.geovault.common.map

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.RetrofitClient
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * In-memory cache for map style JSON so switching layers (e.g. MapTiler street ↔ satellite)
 * can load from cache and feel instant on repeat switches.
 */
object MapStyleCache {

    private val cache = ConcurrentHashMap<String, String>()
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun isMapTilerHost(host: String?): Boolean =
        host != null && (host == "api.maptiler.com" || host.endsWith(".maptiler.com"))

    /** OkHttpClient for fetching external (MapTiler) style URLs; adds Origin/Referer for 403 avoidance. */
    private fun externalStyleClient(context: Context): OkHttpClient {
        val serverUrl = GeovaultAuthManager.getServerUrl(context).trimEnd('/')
        val interceptor = okhttp3.Interceptor { chain ->
            val request = chain.request()
            val host = request.url.host
            val newRequest = if (isMapTilerHost(host) && serverUrl.isNotEmpty()) {
                request.newBuilder()
                    .header("Origin", serverUrl)
                    .header("Referer", "$serverUrl/")
                    .build()
            } else {
                request
            }
            chain.proceed(newRequest)
        }
        return OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Returns style JSON for the given URL, from cache if present otherwise by fetching.
     * [serverBaseForRewrite] e.g. "https://server" when [isOurServer] is true, to rewrite "/api/tiles/" in the JSON.
     * [onResult] is invoked on the main thread with the JSON string, or null on failure.
     */
    fun getStyleJson(
        context: Context,
        styleUrl: String,
        isOurServer: Boolean,
        serverBaseForRewrite: String?,
        onResult: (String?) -> Unit
    ) {
        fun deliver(result: String?) {
            mainHandler.post { onResult(result) }
        }

        cache[styleUrl]?.let { cached ->
            deliver(cached)
            return
        }

        executor.execute {
            try {
                val client = if (isOurServer) {
                    RetrofitClient.getAuthenticatedOkHttpClient(context)
                } else {
                    externalStyleClient(context)
                }
                val request = Request.Builder().url(styleUrl).get().build()
                val response = client.newCall(request).execute()
                var json = response.body?.string()
                if (!response.isSuccessful || json.isNullOrBlank()) {
                    deliver(null)
                    return@execute
                }
                if (isOurServer && !serverBaseForRewrite.isNullOrBlank()) {
                    json = json.replace("\"/api/tiles/", "\"$serverBaseForRewrite/api/tiles/")
                }
                cache[styleUrl] = json
                deliver(json)
            } catch (_: Exception) {
                deliver(null)
            }
        }
    }

    /**
     * Preloads both MapTiler style JSONs (streets and hybrid) in the background when a server is
     * configured and provides them. Call from app start so layer switching is instant.
     */
    fun preloadMapTilerStyles(context: Context) {
        val serverUrl = GeovaultAuthManager.getServerUrl(context).trimEnd('/')
        if (serverUrl.isEmpty()) return
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(context, baseUrl).create(MapApi::class.java)
        api.getTileSources().enqueue(object : Callback<TileSourceResponse> {
            override fun onResponse(call: Call<TileSourceResponse>, response: Response<TileSourceResponse>) {
                val sources = response.body()?.sources ?: return
                for (source in sources) {
                    if (source.id != SOURCE_MAPTILER_STREETS && source.id != SOURCE_MAPTILER_HYBRID) continue
                    val styleUrl = source.client_config.style_url ?: continue
                    val resolved = if (styleUrl.startsWith("/")) "$serverUrl$styleUrl" else styleUrl
                    val isOurServer = resolved == serverUrl || resolved.startsWith("$serverUrl/")
                    val serverBase = if (isOurServer) java.net.URI.create(resolved).let { "${it.scheme}://${it.host}" } else null
                    getStyleJson(context, resolved, isOurServer, serverBase) { }
                }
            }
            override fun onFailure(call: Call<TileSourceResponse>, t: Throwable) { }
        })
    }
}
