package com.geovault.places.data

import android.content.Context
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.places.domain.PlacesRemoteDataSource
import com.geovault.places.model.Feature
import com.geovault.places.model.FeatureCollection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class PlacesRepository(private val context: Context) : PlacesRemoteDataSource {
    private fun api(): PlacesApi {
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        return PlacesApiFactory.create(context, serverUrl)
    }

    fun fetchPlaces(): Result<FeatureCollection> {
        return runCatching {
            GeoVaultCaptureLog.i(TAG, "fetchPlaces start")
            val response = api().getPlaces().execute()
            if (!response.isSuccessful) {
                val message = parseApiError(response, "Server error: ${response.code()}")
                GeoVaultCaptureLog.e(TAG, "fetchPlaces failed code=${response.code()} message=$message")
                error(message)
            }
            val body = response.body() ?: error("Server returned no data")
            GeoVaultCaptureLog.i(TAG, "fetchPlaces ok count=${body.features.size}")
            body
        }.onFailure { err ->
            GeoVaultCaptureLog.e(TAG, "fetchPlaces exception: ${err.message}", err)
        }
    }

    override suspend fun fetchPlacesCancellable(): Result<FeatureCollection> {
        return runCatching {
            GeoVaultCaptureLog.i(TAG, "fetchPlacesCancellable start")
            val call = api().getPlaces()
            executeCancellable(call) { response ->
                if (!response.isSuccessful) {
                    val message = parseApiError(response, "Server error: ${response.code()}")
                    GeoVaultCaptureLog.e(
                        TAG,
                        "fetchPlacesCancellable failed code=${response.code()} message=$message",
                    )
                    error(message)
                }
                val body = response.body() ?: error("Server returned no data")
                GeoVaultCaptureLog.i(TAG, "fetchPlacesCancellable ok count=${body.features.size}")
                body
            }
        }.onFailure { err ->
            GeoVaultCaptureLog.e(TAG, "fetchPlacesCancellable exception: ${err.message}", err)
        }
    }

    override suspend fun fetchPlace(id: Int): Result<Feature> {
        return runCatching {
            GeoVaultCaptureLog.i(TAG, "fetchPlace start id=$id")
            val call = api().getPlace(id)
            executeCancellable(call) { response ->
                if (!response.isSuccessful) {
                    val message = parseApiError(response, "Sync failed: server error ${response.code()}")
                    GeoVaultCaptureLog.e(
                        TAG,
                        "fetchPlace failed id=$id code=${response.code()} message=$message",
                    )
                    error(message)
                }
                val body = response.body() ?: error("Server returned no data")
                GeoVaultCaptureLog.i(
                    TAG,
                    "fetchPlace ok id=$id name=${body.properties.name.orEmpty()}",
                )
                body
            }
        }.onFailure { err ->
            GeoVaultCaptureLog.e(TAG, "fetchPlace exception id=$id: ${err.message}", err)
        }
    }

    override suspend fun createPlace(feature: Feature): Result<Feature> {
        val placeName = feature.properties.name.orEmpty()
        return runCatching {
            val body = PlaceWriteBody.fromFeature(feature)
            GeoVaultCaptureLog.i(
                TAG,
                "createPlace start name=$placeName " +
                    "hasDescription=${!body.properties.description.isNullOrBlank()} " +
                    "hasAddress=${!body.properties.address.isNullOrBlank()}",
            )
            val call = api().createPlace(body)
            executeCancellable(call) { response ->
                if (!response.isSuccessful) {
                    val message = parseApiError(response, "Sync failed: server error ${response.code()}")
                    GeoVaultCaptureLog.e(
                        TAG,
                        "createPlace failed name=$placeName code=${response.code()} message=$message",
                    )
                    error(message)
                }
                val created = response.body() ?: error("Server returned no data")
                GeoVaultCaptureLog.i(
                    TAG,
                    "createPlace ok name=$placeName serverId=${created.properties.database_id}",
                )
                created
            }
        }.onFailure { err ->
            GeoVaultCaptureLog.e(TAG, "createPlace exception name=$placeName: ${err.message}", err)
        }
    }

    override suspend fun updatePlace(id: Int, feature: Feature): Result<Feature> {
        val placeName = feature.properties.name.orEmpty()
        return runCatching {
            val body = PlaceWriteBody.fromFeature(feature)
            GeoVaultCaptureLog.i(
                TAG,
                "updatePlace start id=$id name=$placeName " +
                    "hasDescription=${!body.properties.description.isNullOrBlank()} " +
                    "hasAddress=${!body.properties.address.isNullOrBlank()}",
            )
            val call = api().updatePlace(id, body)
            executeCancellable(call) { response ->
                if (!response.isSuccessful) {
                    val message = parseApiError(response, "Sync failed: server error ${response.code()}")
                    GeoVaultCaptureLog.e(
                        TAG,
                        "updatePlace failed id=$id name=$placeName code=${response.code()} message=$message",
                    )
                    error(message)
                }
                val updated = response.body() ?: error("Server returned no data")
                GeoVaultCaptureLog.i(TAG, "updatePlace ok id=$id name=$placeName")
                updated
            }
        }.onFailure { err ->
            GeoVaultCaptureLog.e(TAG, "updatePlace exception id=$id name=$placeName: ${err.message}", err)
        }
    }

    fun deletePlace(id: Int): Result<Unit> {
        return runCatching {
            GeoVaultCaptureLog.i(TAG, "deletePlace start id=$id")
            val response = api().deletePlace(id).execute()
            if (!response.isSuccessful) {
                val message = parseApiError(response, "Failed to delete place: ${response.code()}")
                GeoVaultCaptureLog.e(TAG, "deletePlace failed id=$id code=${response.code()} message=$message")
                error(message)
            }
            GeoVaultCaptureLog.i(TAG, "deletePlace ok id=$id")
            Unit
        }.onFailure { err ->
            GeoVaultCaptureLog.e(TAG, "deletePlace exception id=$id: ${err.message}", err)
        }
    }

    private suspend fun <T> executeCancellable(
        call: Call<T>,
        mapper: (Response<T>) -> T,
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
            if (message.isNotEmpty()) {
                // Keep HTTP code discoverable for GeoVaultHttpFailureClassifier.
                "$message (HTTP ${response.code()})"
            } else {
                fallback
            }
        } catch (_: Exception) {
            fallback
        }
    }

    companion object {
        private const val TAG = "PlacesApi"
    }
}
