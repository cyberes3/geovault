package com.geovault.common.maps.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.RetrofitClient
import com.geovault.common.maps.model.SOURCE_MAPTILER_HYBRID
import com.geovault.common.maps.model.SOURCE_MAPTILER_STREETS
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object MapStyleCache {
    private val cache = ConcurrentHashMap<String, String>()
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun isMapTilerHost(host: String?): Boolean {
        return host != null && (host == "api.maptiler.com" || host.endsWith(".maptiler.com"))
    }

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

    fun getStyleJson(
        context: Context,
        styleUrl: String,
        isOurServer: Boolean,
        serverBaseForRewrite: String?,
        onResult: (String?) -> Unit,
    ) {
        fun deliver(result: String?) {
            mainHandler.post { onResult(result) }
        }

        cache[styleUrl]?.let {
            deliver(it)
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
                val json = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(
                        TAG,
                        "Map style fetch failed (OSM fallback may be used): code=${response.code} " +
                            "message=${response.message} isOurServer=$isOurServer url=$styleUrl " +
                            "bodyPreview=${json.take(BODY_PREVIEW_MAX)}",
                    )
                    deliver(null)
                    return@execute
                }
                if (json.isBlank()) {
                    Log.w(
                        TAG,
                        "Map style fetch returned empty body (OSM fallback may be used): url=$styleUrl isOurServer=$isOurServer",
                    )
                    deliver(null)
                    return@execute
                }
                var rewritten = json
                if (isOurServer && !serverBaseForRewrite.isNullOrBlank()) {
                    rewritten = rewritten.replace("\"/api/tiles/", "\"$serverBaseForRewrite/api/tiles/")
                }
                cache[styleUrl] = rewritten
                deliver(rewritten)
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Map style fetch threw (OSM fallback may be used): isOurServer=$isOurServer url=$styleUrl",
                    e,
                )
                deliver(null)
            }
        }
    }

    fun preloadMapTilerStyles(context: Context) {
        TileSourceCache.getTileSources(context) { sources ->
            if (sources == null) return@getTileSources
            val serverUrl = GeovaultAuthManager.getServerUrl(context).trimEnd('/')
            for (source in sources) {
                if (source.id != SOURCE_MAPTILER_STREETS && source.id != SOURCE_MAPTILER_HYBRID) continue
                val styleUrl = source.client_config.style_url ?: continue
                val resolved = if (styleUrl.startsWith("/")) "$serverUrl$styleUrl" else styleUrl
                val isOurServer = resolved == serverUrl || resolved.startsWith("$serverUrl/")
                val serverBase = if (isOurServer) {
                    java.net.URI.create(resolved).let { "${it.scheme}://${it.host}" }
                } else {
                    null
                }
                getStyleJson(context, resolved, isOurServer, serverBase) { }
            }
        }
    }

    private const val TAG = "MapStyleCache"
    private const val BODY_PREVIEW_MAX = 400
}
