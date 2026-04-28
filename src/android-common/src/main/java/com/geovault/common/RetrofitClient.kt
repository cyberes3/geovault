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
        val token = GeovaultAuthManager.getAccessToken(appContext)
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
