package com.geovault.common.maps.core

import android.content.Context
import android.util.Log
import com.geovault.common.AppResetFlow
import com.geovault.common.GeovaultAuthManager
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Route
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.storage.FileSource
import java.util.concurrent.TimeUnit

/**
 * One-shot MapLibre subsystem initializer for GeoVault apps.
 *
 * Wires (in order, idempotently):
 *  1. `MapLibre.getInstance` so the native bits are loaded.
 *  2. The global OkHttp client used for every MapLibre HTTP request:
 *       - rejected-sentinel short-circuit (front of chain)
 *       - MapTiler Origin/Referer header injection
 *       - GeoVault Bearer-token injection + 401/403 handling + refresh
 *  3. [MapResourceUrlTransform] as the engine-level URL veto + server-relative
 *     rewrite. Installed via [FileSource.setResourceTransform].
 *  4. A [AppResetFlow] hook that invalidates [MapStyleCache] before token
 *     clear so a stale cached style is never replayed against a different
 *     auth context.
 *  5. Pre-fetch of MapTiler style.json for warmer first-paint.
 */
object MapLibreInitializer {

    private const val TAG = "MapLibreInitializer"
    private const val HOOK_INVALIDATE_STYLE_CACHE = "geovault_maps_style_cache_invalidate"
    private const val MAPLIBRE_CLIENT_TIMEOUT_SECONDS = 30L

    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val appContext = context.applicationContext
        MapLibre.getInstance(appContext)
        configureMapLibreHttpClient(appContext)
        installResourceTransform(appContext)
        registerStyleCacheResetHook()
        MapStyleCache.preloadMapTilerStyles(appContext)
        initialized = true
        Log.d(TAG, "MapLibre subsystem initialized")
    }

    private fun configureMapLibreHttpClient(appContext: Context) {
        val client = OkHttpClient.Builder()
            // Front of chain: short-circuit any URL the resource transform vetoed.
            .addInterceptor(RejectedSentinelInterceptor())
            .addInterceptor(MapTilerOriginInterceptor(appContext))
            .addInterceptor(GeoVaultAuthInterceptor(appContext))
            .addInterceptor(GeoVaultAuthFailureInterceptor(appContext))
            .authenticator(GeoVaultTokenAuthenticator(appContext))
            .connectTimeout(MAPLIBRE_CLIENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(MAPLIBRE_CLIENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(MAPLIBRE_CLIENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        HttpRequestUtil.setOkHttpClient(client)
        HttpRequestUtil.setLogEnabled(true)
        HttpRequestUtil.setPrintRequestUrlOnFailure(true)
    }

    private fun installResourceTransform(appContext: Context) {
        FileSource.getInstance(appContext).setResourceTransform(MapResourceUrlTransform(appContext))
    }

    private fun registerStyleCacheResetHook() {
        AppResetFlow.registerHook(
            key = HOOK_INVALIDATE_STYLE_CACHE,
            phase = AppResetFlow.Phase.BEFORE_TOKEN_CLEAR,
        ) { _ -> MapStyleCache.invalidate() }
    }
}

/**
 * Adds `Authorization: Bearer <token>` to outgoing requests bound for the
 * configured GeoVault server. No-ops for any other host (e.g. MapTiler,
 * tile.openstreetmap.org), so this can sit in front of every request.
 */
internal class GeoVaultAuthInterceptor(
    context: Context,
) : Interceptor {

    private val appContext = context.applicationContext

    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val serverUrl = GeovaultAuthManager.getServerUrl(appContext).trimEnd('/')
        val urlString = request.url.toString()
        val isOurServer = serverUrl.isNotEmpty() &&
            (urlString == serverUrl || urlString.startsWith("$serverUrl/"))
        if (!isOurServer) return chain.proceed(request)
        val token = GeovaultAuthManager.getAccessToken(appContext)
        val outgoing = if (!token.isNullOrBlank()) {
            request.newBuilder().header("Authorization", "Bearer $token").build()
        } else {
            request
        }
        return chain.proceed(outgoing)
    }
}

/**
 * Triggers an app-wide auth reset on persistent 401/403 from the GeoVault
 * server. 403 is treated as terminal; 401 only triggers reset after the
 * refresh-token retry path (signaled by the `X-Geovault-Retry` header) has
 * also failed.
 */
internal class GeoVaultAuthFailureInterceptor(
    context: Context,
) : Interceptor {

    private val appContext = context.applicationContext

    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val response = chain.proceed(chain.request())
        val request = response.request
        when {
            response.code == 403 -> GeovaultAuthManager.handleAuthFailure(appContext)
            response.code == 401 && request.header("X-Geovault-Retry") != null ->
                GeovaultAuthManager.handleAuthFailure(appContext)
        }
        return response
    }
}

/**
 * OkHttp [Authenticator] that exchanges a refresh token for a fresh access
 * token on a 401 from the GeoVault server, then replays the request with
 * the new bearer + an `X-Geovault-Retry` marker so
 * [GeoVaultAuthFailureInterceptor] can recognize a *second* 401 as terminal.
 */
internal class GeoVaultTokenAuthenticator(
    context: Context,
) : Authenticator {

    private val appContext = context.applicationContext

    override fun authenticate(route: Route?, response: okhttp3.Response): okhttp3.Request? {
        if (response.priorResponse?.code == 401) return null
        val serverUrl = GeovaultAuthManager.getServerUrl(appContext).trimEnd('/')
        val urlString = response.request.url.toString()
        if (serverUrl.isEmpty() || !urlString.startsWith("$serverUrl/")) return null
        val newToken = try {
            GeovaultAuthManager.getValidAccessToken(appContext, forceRefreshForToken = null)
        } catch (_: Exception) {
            return null
        }
        if (newToken.isNullOrBlank()) return null
        return response.request.newBuilder()
            .header("Authorization", "Bearer $newToken")
            .header("X-Geovault-Retry", "true")
            .build()
    }
}
