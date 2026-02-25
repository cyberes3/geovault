package com.geovault.common

import android.content.Context
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Builds an authenticated Retrofit instance for the GeoVault API.
 * Uses [GeovaultAuthManager] for the server URL and Bearer token; retries on 401 with a refreshed token.
 *
 * Use application context when creating long-lived clients to avoid leaking activities.
 */
object RetrofitClient {

    fun getClient(context: Context, baseUrl: String): Retrofit {
        val appContext = context.applicationContext
        val tokenProvider = { GeovaultAuthManager.getValidAccessToken(appContext) }
        val authInterceptor = Interceptor { chain ->
            val token = tokenProvider()
            val request = if (!token.isNullOrBlank()) {
                chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $token")
                    .build()
            } else {
                chain.request()
            }
            val response = chain.proceed(request)
            if (response.code == 401) {
                val newToken = GeovaultAuthManager.getValidAccessToken(appContext)
                if (!newToken.isNullOrBlank()) {
                    response.close()
                    return@Interceptor chain.proceed(
                        chain.request().newBuilder()
                            .addHeader("Authorization", "Bearer $newToken")
                            .build()
                    )
                }
                GeovaultAuthManager.clearTokens(appContext)
            }
            response
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
}
