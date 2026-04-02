package com.geovault.places.data

import android.content.Context
import com.geovault.common.GeovaultAuthManager
import com.geovault.places.domain.PlacesRemoteDataSource
import com.geovault.places.model.AddressSearchResult
import com.geovault.places.model.Feature
import com.geovault.places.model.FeatureCollection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import org.json.JSONObject

class PlacesRepository(private val context: Context) : PlacesRemoteDataSource {
    private fun api(): PlacesApi {
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        return PlacesApiFactory.create(context, serverUrl)
    }

    fun fetchPlaces(): Result<FeatureCollection> {
        return runCatching {
            val response = api().getPlaces().execute()
            if (!response.isSuccessful) error("Server error: ${response.code()}")
            response.body() ?: error("Server returned no data")
        }
    }

    override suspend fun fetchPlacesCancellable(): Result<FeatureCollection> {
        return runCatching {
            val call = api().getPlaces()
            executeCancellable(call) { response ->
                if (!response.isSuccessful) {
                    error("Server error: ${response.code()}")
                }
                response.body() ?: error("Server returned no data")
            }
        }
    }

    override fun fetchPlace(id: Int): Result<Feature> {
        return runCatching {
            val response = api().getPlace(id).execute()
            if (!response.isSuccessful) error(parseApiError(response, "Sync failed: server error ${response.code()}"))
            response.body() ?: error("Server returned no data")
        }
    }

    override fun createPlace(feature: Feature): Result<Feature> {
        return runCatching {
            val response = api().createPlace(feature).execute()
            if (!response.isSuccessful) error(parseApiError(response, "Sync failed: server error ${response.code()}"))
            response.body() ?: error("Server returned no data")
        }
    }

    override fun updatePlace(id: Int, feature: Feature): Result<Feature> {
        return runCatching {
            val response = api().updatePlace(id, feature).execute()
            if (!response.isSuccessful) error(parseApiError(response, "Sync failed: server error ${response.code()}"))
            response.body() ?: error("Server returned no data")
        }
    }

    fun deletePlace(id: Int): Result<Unit> {
        return runCatching {
            val response = api().deletePlace(id).execute()
            if (!response.isSuccessful) error("Failed to delete place: ${response.code()}")
            Unit
        }
    }

    fun geocodingSearch(query: String): Result<List<AddressSearchResult>> {
        return runCatching {
            val response = api().geocodingSearch(query).execute()
            if (!response.isSuccessful) error("Geocoding failed: ${response.code()}")
            response.body()?.data?.features.orEmpty()
        }
    }

    private suspend fun <T> executeCancellable(
        call: Call<T>,
        mapper: (Response<T>) -> T
    ): T = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback<T> {
            override fun onResponse(call: Call<T>, response: Response<T>) {
                if (continuation.isCancelled) return
                runCatching { mapper(response) }
                    .onSuccess { continuation.resume(it) }
                    .onFailure { continuation.resumeWithException(it) }
            }

            override fun onFailure(call: Call<T>, t: Throwable) {
                if (continuation.isCancelled) return
                continuation.resumeWithException(t)
            }
        })
    }

    private fun parseApiError(response: Response<*>, fallback: String): String {
        val body = response.errorBody()?.string() ?: return fallback
        return try {
            val json = JSONObject(body)
            val message = json.optString("error", "").trim()
            if (message.isNotEmpty()) message else fallback
        } catch (_: Exception) {
            fallback
        }
    }
}
