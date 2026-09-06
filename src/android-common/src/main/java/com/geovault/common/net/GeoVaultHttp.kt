package com.geovault.common.net

import android.content.Context
import com.geovault.common.auth.GeoVaultAuthSession
import com.google.gson.GsonBuilder
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Route
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Shared HTTP clients for authenticated API traffic, OAuth bootstrap, and reachability probes.
 *
 * Bind once from [GeoVaultAuthSession.create]. Authenticated interceptors attach a cached
 * access token; refresh happens only in the OkHttp [Authenticator].
 */
object GeoVaultHttp {
    private val networkGson = GsonBuilder().serializeNulls().create()
    private val networkGsonOmitNulls = GsonBuilder().create()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var session: GeoVaultAuthSession? = null

    @Volatile
    private var authenticated: OkHttpClient? = null

    @Volatile
    private var probe: OkHttpClient? = null

    @Volatile
    private var bootstrap: OkHttpClient? = null

    private val apiCacheLock = Any()
    private val apiCache = mutableMapOf<String, Any>()

    enum class GsonMode { SerializeNulls, OmitNulls }

    fun bind(context: Context, authSession: GeoVaultAuthSession) {
        appContext = context.applicationContext
        session = authSession
        authenticated = buildAuthenticatedClient(context.applicationContext, authSession)
        if (probe == null) {
            probe = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        }
        if (bootstrap == null) {
            bootstrap = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
        invalidateCaches()
    }

    fun authenticatedClient(): OkHttpClient {
        return authenticated ?: error("GeoVaultHttp.bind() has not been called")
    }

    fun probeClient(): OkHttpClient {
        return probe ?: OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
            .also { probe = it }
    }

    fun bootstrapClient(): OkHttpClient {
        return bootstrap ?: OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
            .also { bootstrap = it }
    }

    fun webSocketClient(
        readTimeoutSec: Long = 90L,
        writeTimeoutSec: Long = 10L,
        connectTimeoutSec: Long = 15L,
        pingIntervalSec: Long = 30L,
    ): OkHttpClient {
        val auth = session ?: error("GeoVaultHttp.bind() has not been called")
        val context = appContext ?: error("GeoVaultHttp.bind() has not been called")
        return OkHttpClient.Builder()
            .addInterceptor(authTokenInterceptor(auth))
            .addInterceptor(authFailureInterceptor(context, auth))
            .authenticator(tokenAuthenticator(context, auth))
            .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .writeTimeout(writeTimeoutSec, TimeUnit.SECONDS)
            .pingInterval(pingIntervalSec, TimeUnit.SECONDS)
            .build()
    }

    fun <T> api(
        apiClass: Class<T>,
        base: GeoVaultServerUrl,
        mode: GsonMode = GsonMode.OmitNulls,
    ): T {
        val cacheKey = "${apiClass.name}|${base.asRetrofitBase()}|$mode"
        synchronized(apiCacheLock) {
            val hit = apiCache[cacheKey]
            if (hit != null) {
                @Suppress("UNCHECKED_CAST")
                return hit as T
            }
            val created = retrofit(base, mode).create(apiClass)
            apiCache[cacheKey] = created as Any
            return created
        }
    }

    fun <T> createCachedApi(
        base: GeoVaultServerUrl,
        apiClass: Class<T>,
        cache: CachedApiHolder<T>,
        mode: GsonMode = GsonMode.OmitNulls,
    ): T {
        val normalized = base.asRetrofitBase()
        synchronized(cache.lock) {
            val hit = cache.api
            if (hit != null && cache.baseUrl == normalized) return hit
            val created = retrofit(base, mode).create(apiClass)
            cache.baseUrl = normalized
            cache.api = created
            return created
        }
    }

    fun invalidateCaches() {
        synchronized(apiCacheLock) {
            apiCache.clear()
        }
    }

    private fun retrofit(base: GeoVaultServerUrl, mode: GsonMode): Retrofit {
        val gson = when (mode) {
            GsonMode.SerializeNulls -> networkGson
            GsonMode.OmitNulls -> networkGsonOmitNulls
        }
        return Retrofit.Builder()
            .baseUrl(base.asRetrofitBase())
            .client(authenticatedClient())
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    private fun buildAuthenticatedClient(context: Context, auth: GeoVaultAuthSession): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(authTokenInterceptor(auth))
            .addInterceptor(authFailureInterceptor(context, auth))
            .authenticator(tokenAuthenticator(context, auth))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun authTokenInterceptor(auth: GeoVaultAuthSession): Interceptor = Interceptor { chain ->
        val token = auth.cachedAccessToken()
        val request = if (!token.isNullOrBlank()) {
            chain.request().newBuilder().header("Authorization", "Bearer $token").build()
        } else {
            chain.request()
        }
        chain.proceed(request)
    }

    private fun authFailureInterceptor(context: Context, auth: GeoVaultAuthSession): Interceptor =
        Interceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.code == 401 && response.request.header(RETRY_HEADER) != null) {
                auth.handleAuthFailure()
            }
            response
        }

    private fun tokenAuthenticator(context: Context, auth: GeoVaultAuthSession): Authenticator =
        Authenticator { _: Route?, response ->
            if (response.priorResponse?.code == 401) return@Authenticator null
            val authHeader = response.request.header("Authorization")
            val failedToken = if (authHeader?.startsWith("Bearer ") == true) {
                authHeader.substring("Bearer ".length)
            } else {
                null
            }
            val newToken = try {
                auth.refreshAccessToken(failedToken)
            } catch (_: java.io.IOException) {
                return@Authenticator null
            }
            if (newToken.isNullOrBlank()) {
                auth.handleAuthFailure()
                return@Authenticator null
            }
            response.request.newBuilder()
                .header("Authorization", "Bearer $newToken")
                .header(RETRY_HEADER, "true")
                .build()
        }

    class CachedApiHolder<T> {
        internal val lock = Any()
        @Volatile internal var baseUrl: String? = null
        @Volatile internal var api: T? = null

        fun clear() {
            synchronized(lock) {
                baseUrl = null
                api = null
            }
        }
    }

    private const val RETRY_HEADER = "X-Geovault-Retry"
}
