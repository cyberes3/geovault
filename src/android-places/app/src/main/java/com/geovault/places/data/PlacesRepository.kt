package com.geovault.places.data

import com.geovault.common.auth.GeoVaultAuthSession
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.common.net.GeoVaultApiFailure
import com.geovault.common.net.GeoVaultHttp
import com.geovault.common.net.GeoVaultServerUrl
import com.geovault.common.net.await
import com.geovault.common.net.awaitResponse
import com.geovault.common.net.successOrThrow
import com.geovault.places.domain.PlacesRemoteDataSource
import com.geovault.places.model.Place

class PlacesRepository(
    private val session: GeoVaultAuthSession = GeoVaultAuthSession.get(),
    private val apiCache: GeoVaultHttp.CachedApiHolder<PlacesApi> = GeoVaultHttp.CachedApiHolder(),
) : PlacesRemoteDataSource {
    override suspend fun fetchPlaces(): List<Place> {
        GeoVaultCaptureLog.i(TAG, "fetchPlaces start")
        val collection = api().getPlaces().await("fetchPlaces")
        val places = collection.features.mapNotNull { it.toPlaceOrNull() }
        GeoVaultCaptureLog.i(TAG, "fetchPlaces ok count=${places.size}")
        return places
    }

    override suspend fun fetchPlace(id: Int): Place {
        GeoVaultCaptureLog.i(TAG, "fetchPlace start id=$id")
        val feature = api().getPlace(id).await("fetchPlace")
        val place = feature.toPlaceOrNull()
            ?: throw GeoVaultApiFailure(httpCode = null, serverMessage = "Server returned no data", operation = "fetchPlace")
        GeoVaultCaptureLog.i(TAG, "fetchPlace ok id=$id name=${place.content.name}")
        return place
    }

    override suspend fun createPlace(place: Place): Place {
        val placeName = place.content.name
        GeoVaultCaptureLog.i(
            TAG,
            "createPlace start name=$placeName " +
                "hasDescription=${place.content.description.isNotBlank()} " +
                "hasAddress=${!place.content.address.isNullOrBlank()}",
        )
        val created = api().createPlace(PlaceWriteBody.fromPlace(place)).await("createPlace")
        val mapped = created.toPlaceOrNull()
            ?: throw GeoVaultApiFailure(httpCode = null, serverMessage = "Server returned no data", operation = "createPlace")
        GeoVaultCaptureLog.i(TAG, "createPlace ok name=$placeName serverId=${mapped.serverId}")
        return mapped
    }

    override suspend fun updatePlace(id: Int, place: Place): Place {
        val placeName = place.content.name
        GeoVaultCaptureLog.i(
            TAG,
            "updatePlace start id=$id name=$placeName " +
                "hasDescription=${place.content.description.isNotBlank()} " +
                "hasAddress=${!place.content.address.isNullOrBlank()}",
        )
        val updated = api().updatePlace(id, PlaceWriteBody.fromPlace(place)).await("updatePlace")
        val mapped = updated.toPlaceOrNull()
            ?: throw GeoVaultApiFailure(httpCode = null, serverMessage = "Server returned no data", operation = "updatePlace")
        GeoVaultCaptureLog.i(TAG, "updatePlace ok id=$id name=$placeName")
        return mapped
    }

    override suspend fun deletePlace(id: Int) {
        GeoVaultCaptureLog.i(TAG, "deletePlace start id=$id")
        val response = api().deletePlace(id).awaitResponse("deletePlace")
        response.successOrThrow("deletePlace")
        GeoVaultCaptureLog.i(TAG, "deletePlace ok id=$id")
    }

    fun clearApiCache() {
        apiCache.clear()
    }

    private fun api(): PlacesApi {
        val url = session.getServerUrl()
        if (url.isBlank()) {
            throw GeoVaultApiFailure(httpCode = null, serverMessage = "Missing server URL")
        }
        val parsed = GeoVaultServerUrl.parse(url)
            ?: throw GeoVaultApiFailure(httpCode = null, serverMessage = "Missing server URL")
        return GeoVaultHttp.createCachedApi(parsed, PlacesApi::class.java, apiCache)
    }

    companion object {
        private const val TAG = "PlacesApi"
    }
}
