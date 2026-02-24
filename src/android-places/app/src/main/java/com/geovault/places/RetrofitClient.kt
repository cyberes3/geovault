package com.geovault.places

import android.content.Context
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    fun getClient(context: Context, baseUrl: String): Retrofit {
        val tokenProvider = { GeovaultAuthManager.getValidAccessToken(context) }
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
                val refreshToken = GeovaultAuthManager.getRefreshToken(context)
                val serverUrl = GeovaultAuthManager.getServerUrl(context)
                var retried = false
                if (!refreshToken.isNullOrBlank() && serverUrl.isNotBlank()) {
                    var newAccess: String? = null
                    var newRefresh: String? = null
                    var newExpires: Long = 0L
                    GeovaultAuthManager.refreshAccessToken(
                        serverUrl, refreshToken,
                        onSuccess = { access, newRt, expires ->
                            newAccess = access
                            newRefresh = newRt
                            newExpires = expires
                        },
                        onError = { }
                    )
                    if (newAccess != null && newExpires > 0) {
                        GeovaultAuthManager.saveTokens(
                            context, newAccess!!, newRefresh ?: refreshToken, newExpires
                        )
                        response.close()
                        retried = true
                        return@Interceptor chain.proceed(
                            chain.request().newBuilder()
                                .addHeader("Authorization", "Bearer $newAccess")
                                .build()
                        )
                    }
                }
                if (!retried) {
                    GeovaultAuthManager.clearTokens(context)
                }
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
