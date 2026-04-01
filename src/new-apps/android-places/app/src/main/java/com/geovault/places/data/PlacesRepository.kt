package com.geovault.places.data

import android.content.Context
import com.geovault.common.GeovaultAuthManager
import com.geovault.places.model.Feature
import com.geovault.places.model.FeatureCollection

class PlacesRepository(private val context: Context) {
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

    fun fetchPlace(id: Int): Result<Feature> {
        return runCatching {
            val response = api().getPlace(id).execute()
            if (!response.isSuccessful) error("Failed to fetch place: ${response.code()}")
            response.body() ?: error("Server returned no data")
        }
    }

    fun createPlace(feature: Feature): Result<Feature> {
        return runCatching {
            val response = api().createPlace(feature).execute()
            if (!response.isSuccessful) error("Failed to create place: ${response.code()}")
            response.body() ?: error("Server returned no data")
        }
    }

    fun updatePlace(id: Int, feature: Feature): Result<Feature> {
        return runCatching {
            val response = api().updatePlace(id, feature).execute()
            if (!response.isSuccessful) error("Failed to update place: ${response.code()}")
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
}
