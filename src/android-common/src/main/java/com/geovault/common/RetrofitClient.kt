package com.geovault.common

import android.content.Context
import com.google.gson.GsonBuilder
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Route
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    private val networkGson = GsonBuilder()
        .serializeNulls()
        .create()
    private val networkGsonOmitNulls = GsonBuilder()
        .create()
    private fun authTokenInterceptor(appContext: Context): Interceptor = Interceptor { chain ->
        val token = GeovaultAuthManager.getValidAccessToken(appContext)
        val request = if (!token.isNullOrBlank()) {
            chain.request().newBuilder().header("Authorization", "Bearer $token").build()
        } else {
            chain.request()
        }
        chain.proceed(request)
    }

    private fun authFailureInterceptor(appContext: Context): Interceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        if (response.code == 401 && response.request.header("X-Geovault-Retry") != null) {
            GeovaultAuthManager.handleAuthFailure(appContext)
        }
        response
    }

    private fun tokenAuthenticator(appContext: Context): Authenticator = Authenticator { _: Route?, response ->
        if (response.priorResponse?.code == 401) return@Authenticator null
        val authHeader = response.request.header("Authorization")
        val failedToken = if (authHeader?.startsWith("Bearer ") == true) {
            authHeader.substring("Bearer ".length)
        } else {
            null
        }
        val newToken = try {
            GeovaultAuthManager.getValidAccessToken(appContext, failedToken)
        } catch (_: java.io.IOException) {
            return@Authenticator null
        }
        if (newToken.isNullOrBlank()) {
            GeovaultAuthManager.handleAuthFailure(appContext)
            return@Authenticator null
        }
        response.request.newBuilder()
            .header("Authorization", "Bearer $newToken")
            .header("X-Geovault-Retry", "true")
            .build()
    }

    fun getAuthenticatedOkHttpClient(context: Context): OkHttpClient {
        val appContext = context.applicationContext
        return OkHttpClient.Builder()
            .addInterceptor(authTokenInterceptor(appContext))
            .addInterceptor(authFailureInterceptor(appContext))
            .authenticator(tokenAuthenticator(appContext))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * An authenticated [OkHttpClient] tuned for a long-lived WebSocket connection rather than
     * ordinary request/response calls: `readTimeout` and `pingInterval` need to be much larger
     * than [getAuthenticatedOkHttpClient]'s request-oriented defaults (a WS read timeout is
     * really "how long with no frame at all before we consider the socket dead", not a
     * per-request budget), while still carrying the same bearer-token/refresh interceptor and
     * authenticator so a socket upgrade request is authenticated the same way any other API call
     * is. Auth failures are handled by the caller's own `onFailure`/close-code classification (a
     * WebSocket connection doesn't get a mid-stream 401 retried the way an HTTP call does), so
     * the interceptor's role here is limited to attaching a valid token to the initial upgrade
     * request.
     */
    fun newAuthenticatedWebSocketClient(
        context: Context,
        readTimeoutSec: Long = 90L,
        writeTimeoutSec: Long = 10L,
        connectTimeoutSec: Long = 15L,
        pingIntervalSec: Long = 30L,
    ): OkHttpClient {
        val appContext = context.applicationContext
        return OkHttpClient.Builder()
            .addInterceptor(authTokenInterceptor(appContext))
            .addInterceptor(authFailureInterceptor(appContext))
            .authenticator(tokenAuthenticator(appContext))
            .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .writeTimeout(writeTimeoutSec, TimeUnit.SECONDS)
            .pingInterval(pingIntervalSec, TimeUnit.SECONDS)
            .build()
    }

    private fun buildHttpClient(context: Context): OkHttpClient {
        val appContext = context.applicationContext
        return OkHttpClient.Builder()
            .addInterceptor(authTokenInterceptor(appContext))
            .addInterceptor(authFailureInterceptor(appContext))
            .authenticator(tokenAuthenticator(appContext))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun getClient(context: Context, baseUrl: String): Retrofit {
        val client = buildHttpClient(context)
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(networkGson))
            .build()
    }

    fun getClientOmitNulls(context: Context, baseUrl: String): Retrofit {
        val client = buildHttpClient(context)
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(networkGsonOmitNulls))
            .build()
    }
}
