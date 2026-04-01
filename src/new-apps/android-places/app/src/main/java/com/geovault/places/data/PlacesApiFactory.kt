package com.geovault.places.data

import android.content.Context
import com.geovault.common.RetrofitClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object PlacesApiFactory {
    fun create(context: Context, baseUrl: String): PlacesApi {
        val normalizedBase = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(normalizedBase)
            .client(RetrofitClient.getAuthenticatedOkHttpClient(context))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PlacesApi::class.java)
    }
}
