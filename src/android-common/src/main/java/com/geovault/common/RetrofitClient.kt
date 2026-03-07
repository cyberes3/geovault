package com.geovault.common

import android.content.Context
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Route
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Builds an authenticated Retrofit instance for the GeoVault API.
 *
 * Uses the recommended OkHttp pattern for token refresh:
 * - **Interceptor**: Adds the current access token to every request (no refresh; uses [GeovaultAuthManager.getAccessToken]).
 * - **Authenticator**: Invoked by OkHttp on 401 responses; refreshes the token via [GeovaultAuthManager.getValidAccessToken]
 *   and returns a new request. Prevents infinite retry by returning null when the response is already a retry (priorResponse
 *   was 401). Token refresh is serialized inside getValidAccessToken so concurrent 401s do not trigger multiple refresh calls.
 *
 * Use application context when creating long-lived clients to avoid leaking activities.
 */
object RetrofitClient {

    /**
     * Adds the current access token to requests. Does not perform refresh; expired tokens result in 401,
     * which OkHttp handles by invoking the [TokenAuthenticator].
     */
    private fun authTokenInterceptor(appContext: Context): Interceptor = Interceptor { chain ->
        val token = GeovaultAuthManager.getAccessToken(appContext)
        val request = if (!token.isNullOrBlank()) {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            chain.request()
        }
        chain.proceed(request)
    }

    /**
     * Monitors 401 and 403 responses.
     * For 403, it triggers auth failure immediately.
     * For 401, it only triggers if it was already retried (via Authenticator) and still failed.
     * Initial 401s that fail refresh are handled in [tokenAuthenticator].
     */
    private fun authFailureInterceptor(appContext: Context): Interceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        if (response.code == 403) {
            GeovaultAuthManager.handleAuthFailure(appContext)
        } else if (response.code == 401) {
            // Check if this was already a retry attempt
            val isRetry = response.request.header("X-Geovault-Retry") != null
            if (isRetry) {
                GeovaultAuthManager.handleAuthFailure(appContext)
            }
        }
        response
    }

    /**
     * Handles 401 by refreshing the token and retrying once. Return null to stop retries (e.g. refresh failed).
     * OkHttp calls this on a worker thread, so blocking refresh is acceptable.
     */
    private fun tokenAuthenticator(appContext: Context): Authenticator = Authenticator { _: Route?, response: okhttp3.Response ->
        // Avoid infinite retry: if we already retried after a 401, do not retry again
        if (response.priorResponse?.code == 401) {
            return@Authenticator null
        }
        
        // Extract the token that caused the 401 so we can force a refresh if it's still cached
        val authHeader = response.request.header("Authorization")
        val failedToken = if (authHeader != null && authHeader.startsWith("Bearer ")) {
            authHeader.substring("Bearer ".length)
        } else {
            null
        }

        val newToken = try {
            GeovaultAuthManager.getValidAccessToken(appContext, forceRefreshForToken = failedToken)
        } catch (e: java.io.IOException) {
            // A network error occurred while trying to refresh the token (e.g. offline, 500 error).
            // Do NOT clear the token. We return null so OkHttp fails the current request.
            return@Authenticator null
        }
        
        if (newToken.isNullOrBlank()) {
            // Refresh failed or was not possible (no refresh token). Trigger auth failure.
            GeovaultAuthManager.handleAuthFailure(appContext)
            return@Authenticator null
        }
        response.request.newBuilder()
            .header("Authorization", "Bearer $newToken")
            .header("X-Geovault-Retry", "true") // Mark as retry so interceptor knows
            .build()
    }

    /**
     * Returns an OkHttpClient that adds the Bearer token and, on 401, refreshes and retries via OkHttp's Authenticator.
     * Use this for raw HTTP calls (e.g. uploads, status checks).
     * For longer operations: getAuthenticatedOkHttpClient(context).newBuilder().writeTimeout(60, TimeUnit.SECONDS).build()
     */
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

    fun getClient(context: Context, baseUrl: String): Retrofit {
        val appContext = context.applicationContext
        val client = OkHttpClient.Builder()
            .addInterceptor(authTokenInterceptor(appContext))
            .addInterceptor(authFailureInterceptor(appContext))
            .authenticator(tokenAuthenticator(appContext))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
}
