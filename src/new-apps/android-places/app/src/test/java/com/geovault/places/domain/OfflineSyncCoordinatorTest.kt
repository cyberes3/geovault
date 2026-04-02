package com.geovault.places.domain

import com.geovault.places.model.Feature
import com.geovault.places.model.FeatureCollection
import com.geovault.places.model.Geometry
import com.geovault.places.model.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSyncCoordinatorTest {
    @Test
    fun successfulReplayTriggersCanonicalSecondFetch() {
        val firstCollection = FeatureCollection(features = listOf(place(1, "A")))
        val secondCollection = FeatureCollection(features = listOf(place(1, "A2")))
        val repository = SequenceRepo(
            fetchResults = mutableListOf(
                Result.success(firstCollection),
                Result.success(secondCollection),
            )
        )
        val store = CoordinatorStore()
        val syncUseCase = FakeSyncUseCase(
            SyncResult(
                hadQueuedItems = true,
                successCount = 1,
                failedCount = 0,
                queueBecameEmpty = true,
                conflictCount = 0,
                failures = emptyList(),
                events = emptyList(),
            )
        )
        val flusher = CountingFlusher()
        val coordinator = OfflineSyncCoordinator(
            repository = repository,
            cacheStore = store,
            syncExecutor = syncUseCase,
            navigationRetryFlusher = flusher,
            serverUrlProvider = { "https://example.test" },
        )

        val fetchResult = kotlinx.coroutines.runBlocking { coordinator.fetchAndCacheServerSnapshot() }
        val replayResult = kotlinx.coroutines.runBlocking { coordinator.runPendingReplayAndCanonicalRefresh() }

        assertTrue(fetchResult is SnapshotFetchResult.Success)
        assertEquals(2, repository.fetchCount)
        assertEquals(2, flusher.flushCount)
        assertEquals(1, replayResult.syncResult.successCount)
        assertEquals(1, store.cachedSnapshots.last().features.first().properties.database_id)
        assertEquals("A2", store.cachedSnapshots.last().features.first().properties.name)
    }

    @Test
    fun firstFetchFailureReturnsFetchFailed() {
        val repository = SequenceRepo(
            fetchResults = mutableListOf(Result.failure(IllegalStateException("boom")))
        )
        val store = CoordinatorStore()
        val syncUseCase = FakeSyncUseCase(
            SyncResult(
                hadQueuedItems = false,
                successCount = 0,
                failedCount = 0,
                queueBecameEmpty = true,
                conflictCount = 0,
                failures = emptyList(),
                events = emptyList(),
            )
        )
        val coordinator = OfflineSyncCoordinator(
            repository = repository,
            cacheStore = store,
            syncExecutor = syncUseCase,
            navigationRetryFlusher = CountingFlusher(),
            serverUrlProvider = { "https://example.test" },
        )

        val result = kotlinx.coroutines.runBlocking { coordinator.fetchAndCacheServerSnapshot() }

        assertTrue(result is SnapshotFetchResult.Failed)
        assertEquals(0, store.cachedSnapshots.size)
    }

    private fun place(id: Int, name: String): Feature {
        return Feature(
            geometry = Geometry(coordinates = listOf(2.0, 1.0)),
            properties = Properties(
                database_id = id,
                name = name,
                description = null,
                created_at = "2026-01-01",
                address = null,
            ),
        )
    }
}

private class SequenceRepo(
    val fetchResults: MutableList<Result<FeatureCollection>>,
) : PlacesRemoteDataSource {
    var fetchCount: Int = 0
    override suspend fun fetchPlacesCancellable(): Result<FeatureCollection> {
        fetchCount += 1
        return fetchResults.removeFirst()
    }
    override fun fetchPlace(id: Int): Result<Feature> = error("unused")
    override fun createPlace(feature: Feature): Result<Feature> = error("unused")
    override fun updatePlace(id: Int, feature: Feature): Result<Feature> = error("unused")
}

private class CoordinatorStore : PlacesOfflineStore {
    val cachedSnapshots = mutableListOf<FeatureCollection>()
    override fun getOfflineFeatures() = emptyList<com.geovault.places.model.OfflineFeature>()
    override fun removeOffline(item: com.geovault.places.model.OfflineFeature) = Unit
    override fun getCachedFeatures() = cachedSnapshots.lastOrNull()?.features ?: emptyList()
    override fun setCached(collection: FeatureCollection, lastSyncTime: Long) {
        cachedSnapshots.add(collection)
    }
}

private class FakeSyncUseCase(private val result: SyncResult) : OfflineSyncExecutor {
    override suspend fun runSync(): SyncResult = result
}

private class CountingFlusher : NavigationRetryFlusher {
    var flushCount: Int = 0
    override fun flushPending(serverUrl: String) {
        flushCount += 1
    }
}
