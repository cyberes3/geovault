package com.geovault.places.domain

import com.geovault.places.data.PlacesCacheStore
import com.geovault.places.data.PlacesRepository
import com.geovault.places.model.Feature
import com.geovault.places.model.OfflineFeature
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class SyncOfflinePlacesUseCase(
    private val repository: PlacesRepository,
    private val cacheStore: PlacesCacheStore,
) {
    suspend fun runSync(): SyncResult {
        val offline = cacheStore.getOfflineFeatures()
        if (offline.isEmpty()) return SyncResult(0, 0)

        var success = 0
        var failed = 0
        offline.forEach { item ->
            coroutineContext.ensureActive()
            val synced = syncOne(item)
            if (synced) {
                cacheStore.removeOffline(item)
                success += 1
            } else {
                failed += 1
            }
        }
        return SyncResult(success, failed)
    }

    private fun syncOne(item: OfflineFeature): Boolean {
        val feature = item.feature
        val dbId = feature.properties.database_id
        if (dbId == null) {
            return repository.createPlace(feature).isSuccess
        }
        val original = item.original
        if (original == null) {
            return repository.updatePlace(dbId, feature).isSuccess
        }
        val server = repository.fetchPlace(dbId).getOrNull() ?: return false
        if (isChanged(original, server)) {
            val conflicted = feature.copy(
                properties = feature.properties.copy(
                    database_id = null,
                    name = (feature.properties.name ?: "Place") + " - Conflicted",
                )
            )
            return repository.createPlace(conflicted).isSuccess
        }
        return repository.updatePlace(dbId, feature).isSuccess
    }

    private fun isChanged(a: Feature, b: Feature): Boolean {
        if (a.properties.name != b.properties.name) return true
        if (a.properties.description != b.properties.description) return true
        if (a.properties.address != b.properties.address) return true
        return a.geometry.coordinates != b.geometry.coordinates
    }
}

data class SyncResult(
    val successCount: Int,
    val failedCount: Int,
)
