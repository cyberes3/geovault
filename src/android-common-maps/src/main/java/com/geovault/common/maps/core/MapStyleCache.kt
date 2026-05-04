package com.geovault.common.maps.core

import android.content.Context
import android.util.Log
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.RetrofitClient
import com.geovault.common.maps.model.SOURCE_MAPTILER_HYBRID
import com.geovault.common.maps.model.SOURCE_MAPTILER_STREETS
import com.geovault.common.maps.model.SOURCE_MAPTILER_STREETS_DARK
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Asynchronously fetches and caches MapLibre style.json documents.
 *
 * Responsibilities are intentionally narrow:
 *  - Fetch the document over HTTP using an OkHttp client appropriate for the
 *    target host (GeoVault auth for our server, Origin/Referer for MapTiler).
 *  - Run [MapStyleJsonGuards] on the body and treat invalid documents as a
 *    fetch failure.
 *  - Cache the validated body per URL.
 *
 * **Not** this object's job:
 *  - Server-relative URL rewriting — owned by [MapResourceUrlTransform], which
 *    runs at the engine's request layer where every URL flows.
 *  - Auth bookkeeping — owned by [RetrofitClient].
 *
 * Threading: a private [CoroutineScope] on [Dispatchers.IO] owns all fetches.
 * Results are delivered on [Dispatchers.Main] so callers can update MapLibre
 * (which is main-thread-bound) without an extra hop.
 */
internal object MapStyleCache {

    private const val TAG = "MapStyleCache"
    private const val BODY_PREVIEW_MAX = 400
    private const val EXTERNAL_TIMEOUT_SECONDS = 15L

    private val cache = ConcurrentHashMap<String, String>()
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("MapStyleCache"),
    )

    @Volatile
    private var externalClient: OkHttpClient? = null

    fun getStyleJson(
        context: Context,
        styleUrl: String,
        isOurServer: Boolean,
        onResult: (String?) -> Unit,
    ) {
        val cached = cache[styleUrl]
        if (cached != null) {
            scope.launch { withContext(Dispatchers.Main) { onResult(cached) } }
            return
        }
        scope.launch {
            val json = fetchAndValidate(context, styleUrl, isOurServer)
            withContext(Dispatchers.Main) { onResult(json) }
        }
    }

    fun invalidate() {
        cache.clear()
    }

    fun preloadMapTilerStyles(context: Context) {
        TileSourceCache.getTileSources(context) { result ->
            val sources = (result as? TileSourceFetchResult.Success)?.sources ?: return@getTileSources
            val serverUrl = GeovaultAuthManager.getServerUrl(context).trimEnd('/')
            for (source in sources) {
                if (source.id !in PRELOADED_STYLE_SOURCE_IDS) continue
                val styleUrl = source.client_config.style_url ?: continue
                val resolved = if (styleUrl.startsWith("/")) "$serverUrl$styleUrl" else styleUrl
                val isOurServer = resolved == serverUrl || resolved.startsWith("$serverUrl/")
                getStyleJson(context, resolved, isOurServer) { }
            }
        }
    }

    private fun fetchAndValidate(context: Context, styleUrl: String, isOurServer: Boolean): String? {
        val client = if (isOurServer) {
            RetrofitClient.getAuthenticatedOkHttpClient(context)
        } else {
            externalStyleClient(context)
        }
        return try {
            client.newCall(Request.Builder().url(styleUrl).get().build()).execute().use { response ->
                val body = response.body.string()
                val normalizedBody = if (isOurServer) {
                    MapStyleJsonNormalizer.normalizeServerStyle(body)
                } else {
                    body
                }
                when {
                    !response.isSuccessful -> {
                        logHttpFailure(context, styleUrl, isOurServer, response.code, response.message, body)
                        null
                    }
                    normalizedBody.isBlank() -> {
                        logEmptyBody(context, styleUrl, isOurServer)
                        null
                    }
                    MapStyleJsonGuards.hasEmptyOrUnparseableResourceUrl(normalizedBody) -> {
                        Log.e(
                            TAG,
                            "Style JSON contains empty or invalid resource URL(s), or text layers " +
                                "without a glyphs URL; refusing to apply. " +
                                "styleUrl=$styleUrl isOurServer=$isOurServer",
                        )
                        null
                    }
                    else -> {
                        cache[styleUrl] = normalizedBody
                        normalizedBody
                    }
                }
            }
        } catch (e: Exception) {
            logFetchException(context, styleUrl, isOurServer, e)
            null
        }
    }

    private fun externalStyleClient(context: Context): OkHttpClient {
        externalClient?.let { return it }
        synchronized(this) {
            externalClient?.let { return it }
            val client = OkHttpClient.Builder()
                .addInterceptor(MapTilerOriginInterceptor(context))
                .connectTimeout(EXTERNAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(EXTERNAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(EXTERNAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
            externalClient = client
            return client
        }
    }

    private fun logHttpFailure(
        context: Context,
        styleUrl: String,
        isOurServer: Boolean,
        code: Int,
        message: String,
        bodyPreview: String,
    ) {
        val preview = bodyPreview.take(BODY_PREVIEW_MAX)
        if (isOurServer) {
            val configuredServer = GeovaultAuthManager.getServerUrl(context).trimEnd('/')
            Log.e(
                TAG,
                "GeoVault server map style HTTP error: code=$code message=$message " +
                    "styleUrl=$styleUrl configuredServer=$configuredServer bodyPreview=$preview",
            )
        } else {
            Log.w(
                TAG,
                "External map style fetch failed: code=$code message=$message " +
                    "url=$styleUrl bodyPreview=$preview",
            )
        }
    }

    private fun logEmptyBody(context: Context, styleUrl: String, isOurServer: Boolean) {
        if (isOurServer) {
            val configuredServer = GeovaultAuthManager.getServerUrl(context).trimEnd('/')
            Log.e(
                TAG,
                "GeoVault server map style returned empty body. styleUrl=$styleUrl " +
                    "configuredServer=$configuredServer",
            )
        } else {
            Log.w(
                TAG,
                "Map style fetch returned empty body: url=$styleUrl",
            )
        }
    }

    private fun logFetchException(context: Context, styleUrl: String, isOurServer: Boolean, e: Exception) {
        if (isOurServer) {
            val configuredServer = GeovaultAuthManager.getServerUrl(context).trimEnd('/')
            Log.e(
                TAG,
                "GeoVault server map style request failed: styleUrl=$styleUrl configuredServer=$configuredServer",
                e,
            )
        } else {
            Log.e(
                TAG,
                "Map style fetch threw: url=$styleUrl isOurServer=false",
                e,
            )
        }
    }

    private val PRELOADED_STYLE_SOURCE_IDS = setOf(
        SOURCE_MAPTILER_STREETS,
        SOURCE_MAPTILER_STREETS_DARK,
        SOURCE_MAPTILER_HYBRID,
    )
}
