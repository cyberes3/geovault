package com.geovault.places.domain

import com.geovault.places.model.Feature
import com.geovault.places.model.FeatureCollection
import com.geovault.places.model.OfflineFeature

interface PlacesRemoteDataSource {
    suspend fun fetchPlacesCancellable(): Result<FeatureCollection>
    fun fetchPlace(id: Int): Result<Feature>
    fun createPlace(feature: Feature): Result<Feature>
    fun updatePlace(id: Int, feature: Feature): Result<Feature>
}

interface PlacesOfflineStore {
    fun getOfflineFeatures(): List<OfflineFeature>
    fun removeOffline(item: OfflineFeature)
    fun getCachedFeatures(): List<Feature>
    fun setCached(collection: FeatureCollection, lastSyncTime: Long)
}

interface NavigationRetryFlusher {
    fun flushPending(serverUrl: String)
}
