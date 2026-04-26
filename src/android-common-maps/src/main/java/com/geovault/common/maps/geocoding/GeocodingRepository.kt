package com.geovault.common.maps.geocoding

import android.content.Context
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.RetrofitClient
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Authenticated geocoding search against the configured GeoVault server.
 */
class GeocodingRepository(
    context: Context,
) {
    private val appContext = context.applicationContext

    private fun api(): GeocodingApi {
        val serverUrl = GeovaultAuthManager.getServerUrl(appContext)
        val normalizedBase = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        return Retrofit.Builder()
            .baseUrl(normalizedBase)
            .client(RetrofitClient.getAuthenticatedOkHttpClient(appContext))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GeocodingApi::class.java)
    }

    suspend fun search(query: String): Result<List<GeocodeSearchResult>> = withContext(Dispatchers.IO) {
        try {
            val list = executeGeocodeSearchCall(api().geocodingSearch(query))
            Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun executeGeocodeSearchCall(
        call: Call<GeocodeSearchResponse>,
    ): List<GeocodeSearchResult> = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback<GeocodeSearchResponse> {
            override fun onResponse(
                call: Call<GeocodeSearchResponse>,
                response: Response<GeocodeSearchResponse>,
            ) {
                if (continuation.isCancelled) return
                runCatching {
                    if (!response.isSuccessful) {
                        error("Geocoding failed: ${response.code()}")
                    }
                    response.body()?.data?.features.orEmpty()
                }
                    .onSuccess { continuation.resume(it) }
                    .onFailure { continuation.resumeWithException(it) }
            }

            override fun onFailure(call: Call<GeocodeSearchResponse>, t: Throwable) {
                if (continuation.isCancelled) return
                continuation.resumeWithException(t)
            }
        })
    }
}
