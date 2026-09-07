package com.geovault.places.domain

import com.geovault.common.net.GeoVaultApiFailure
import com.geovault.places.model.Feature
import com.geovault.places.model.FeatureCollection
import com.geovault.places.model.Geometry
import com.geovault.places.model.OfflineFeature
import com.geovault.places.model.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncOfflinePlacesUseCaseTest {
    @Test
    fun conflictCreatesNewItemAndRemovesOfflineEntry() {
        val existing = place(id = 10, name = "HQ", desc = "orig")
        val serverChanged = place(id = 10, name = "HQ", desc = "server-new")
        val localOffline = place(id = 10, name = "HQ", desc = "local-new")
        val store = FakeStore(
            offline = mutableListOf(
                OfflineFeature(clientLocalId = "c1", feature = localOffline, original = existing),
            ),
            cached = mutableListOf(),
        )
        val created = localOffline.copy(properties = localOffline.properties.copy(database_id = 99, name = "HQ - Conflicted"))
        val repo = FakeRepo(
            fetchPlaceResult = Result.success(serverChanged),
            createResult = Result.success(created),
        )
        val useCase = SyncOfflinePlacesUseCase(
            repository = repo,
            cacheStore = store,
            conflictResolutionPolicy = ConflictResolutionPolicy(),
        )

        val result = kotlinx.coroutines.runBlocking { useCase.runSync() }

        assertEquals(1, result.successCount)
        assertEquals(0, result.failedCount)
        assertEquals(1, result.conflictCount)
        assertTrue(result.queueBecameEmpty)
        assertEquals(1, repo.createCalls.size)
        assertEquals("HQ - Conflicted", repo.createCalls.first().properties.name)
        assertEquals(listOf(99), store.getCachedFeatures().map { it.properties.database_id })
        assertTrue(result.events.first() is SyncEvent.ConflictSavedAsNew)
    }

    @Test
    fun fetchFailureKeepsOfflineEntry() {
        val existing = place(id = 10, name = "HQ", desc = "orig")
        val localOffline = place(id = 10, name = "HQ", desc = "local-new")
        val store = FakeStore(
            offline = mutableListOf(
                OfflineFeature(clientLocalId = "c1", feature = localOffline, original = existing),
            ),
            cached = mutableListOf(),
        )
        val repo = FakeRepo(
            fetchPlaceResult = Result.failure(IllegalStateException("network")),
        )
        val useCase = SyncOfflinePlacesUseCase(
            repository = repo,
            cacheStore = store,
            conflictResolutionPolicy = ConflictResolutionPolicy(),
        )

        val result = kotlinx.coroutines.runBlocking { useCase.runSync() }

        assertEquals(0, result.successCount)
        assertEquals(1, result.failedCount)
        assertEquals(1, store.getOfflineFeatures().size)
        assertEquals(SyncFailureReason.FetchFailed, result.failures.first().reason)
        assertTrue(result.events.first() is SyncEvent.ItemFailed)
    }

    @Test
    fun writeSuccessAppliesCacheEvenWithoutCanonicalRefresh() {
        val draft = place(id = null, name = "Draft", desc = "new")
        val store = FakeStore(
            offline = mutableListOf(OfflineFeature(clientLocalId = "draft-1", feature = draft)),
            cached = mutableListOf(),
        )
        val server = draft.copy(properties = draft.properties.copy(database_id = 501))
        val repo = FakeRepo(createResult = Result.success(server))
        val useCase = SyncOfflinePlacesUseCase(
            repository = repo,
            cacheStore = store,
            conflictResolutionPolicy = ConflictResolutionPolicy(),
        )

        val result = kotlinx.coroutines.runBlocking { useCase.runSync() }

        assertEquals(1, result.successCount)
        assertTrue(result.queueBecameEmpty)
        assertEquals(501, store.getCachedFeatures().single().properties.database_id)
    }

    @Test
    fun permanentClientErrorKeepsUnsyncedCreate() {
        val draft = place(id = null, name = "Bad", desc = "x")
        val store = FakeStore(
            offline = mutableListOf(OfflineFeature(clientLocalId = "bad-1", feature = draft)),
            cached = mutableListOf(),
        )
        val repo = FakeRepo(
            createResult = Result.failure(GeoVaultApiFailure(httpCode = 400, serverMessage = "Validation failed")),
        )
        val useCase = SyncOfflinePlacesUseCase(
            repository = repo,
            cacheStore = store,
            conflictResolutionPolicy = ConflictResolutionPolicy(),
        )

        val result = kotlinx.coroutines.runBlocking { useCase.runSync() }

        assertEquals(0, result.successCount)
        assertEquals(1, result.failedCount)
        assertEquals(1, store.getOfflineFeatures().size)
        assertEquals("bad-1", store.getOfflineFeatures().single().clientLocalId)
    }

    @Test
    fun permanentClientErrorDropsOfflineEdit() {
        val edited = place(id = 44, name = "HQ", desc = "bad")
        val store = FakeStore(
            offline = mutableListOf(OfflineFeature(clientLocalId = "edit-1", feature = edited)),
            cached = mutableListOf(),
        )
        val repo = FakeRepo(
            updateResult = Result.failure(GeoVaultApiFailure(httpCode = 400, serverMessage = "Validation failed")),
        )
        val useCase = SyncOfflinePlacesUseCase(
            repository = repo,
            cacheStore = store,
            conflictResolutionPolicy = ConflictResolutionPolicy(),
        )

        val result = kotlinx.coroutines.runBlocking { useCase.runSync() }

        assertEquals(0, result.successCount)
        assertEquals(1, result.failedCount)
        assertTrue(store.getOfflineFeatures().isEmpty())
    }

    @Test
    fun updateNotFoundRecreatesAsCreate() {
        val existing = place(id = 44, name = "Gone", desc = "old")
        val edited = place(id = 44, name = "Gone", desc = "changed")
        val store = FakeStore(
            offline = mutableListOf(
                OfflineFeature(clientLocalId = "u1", feature = edited, original = null),
            ),
            cached = mutableListOf(),
        )
        val recreated = edited.copy(properties = edited.properties.copy(database_id = 900))
        val repo = SequencedRepo(
            createResults = mutableListOf(Result.success(recreated)),
            fetchPlaceResults = mutableListOf(),
            updateResults = mutableListOf(
                Result.failure(GeoVaultApiFailure(httpCode = 404, serverMessage = "Resource not found")),
            ),
        )
        val useCase = SyncOfflinePlacesUseCase(
            repository = repo,
            cacheStore = store,
            conflictResolutionPolicy = ConflictResolutionPolicy(),
        )

        val result = kotlinx.coroutines.runBlocking { useCase.runSync() }

        assertEquals(1, result.successCount)
        assertTrue(result.queueBecameEmpty)
        assertEquals(900, store.getCachedFeatures().single().properties.database_id)
    }

    @Test
    fun mixedCreateSuccessAndUpdateFailure_reportsCountsAndRetainsFailedItem() {
        val newDraft = place(id = null, name = "Draft", desc = "new")
        val existing = place(id = 44, name = "Saved", desc = "old")
        val edited = place(id = 44, name = "Saved", desc = "changed")
        val store = FakeStore(
            offline = mutableListOf(
                OfflineFeature(clientLocalId = "a", feature = newDraft, original = null),
                OfflineFeature(clientLocalId = "b", feature = edited, original = existing),
            ),
            cached = mutableListOf(),
        )
        val repo = SequencedRepo(
            createResults = mutableListOf(
                Result.success(newDraft.copy(properties = newDraft.properties.copy(database_id = 500))),
            ),
            fetchPlaceResults = mutableListOf(Result.success(existing)),
            updateResults = mutableListOf(Result.failure(IllegalStateException("network"))),
        )
        val useCase = SyncOfflinePlacesUseCase(
            repository = repo,
            cacheStore = store,
            conflictResolutionPolicy = ConflictResolutionPolicy(),
        )

        val result = kotlinx.coroutines.runBlocking { useCase.runSync() }

        assertEquals(1, result.successCount)
        assertEquals(1, result.failedCount)
        assertEquals(SyncFailureReason.UpdateFailed, result.failures.first().reason)
        assertEquals(1, store.getOfflineFeatures().size)
        assertEquals(44, store.getOfflineFeatures().first().feature.properties.database_id)
        assertEquals("b", store.getOfflineFeatures().first().clientLocalId)
    }

    private fun place(id: Int?, name: String, desc: String): Feature {
        return Feature(
            geometry = Geometry(coordinates = listOf(2.0, 1.0)),
            properties = Properties(
                database_id = id,
                name = name,
                description = desc,
                created_at = "2026-01-01",
                address = null,
            ),
        )
    }
}

private class FakeStore(
    private val offline: MutableList<OfflineFeature>,
    private val cached: MutableList<Feature>,
) : PlacesOfflineStore {
    override fun getOfflineFeatures(): List<OfflineFeature> = offline.toList()
    override fun removeOffline(clientLocalId: String) {
        offline.removeAll { it.clientLocalId == clientLocalId }
    }
    override fun getCachedFeatures(): List<Feature> = cached.toList()
    override fun setCached(collection: FeatureCollection, lastSyncTime: Long) {
        cached.clear()
        cached.addAll(collection.features)
    }
    override fun applyServerFeature(feature: Feature) {
        val id = feature.properties.database_id
        if (id != null) {
            cached.removeAll { it.properties.database_id == id }
        }
        cached.add(0, feature)
    }
}

private class FakeRepo(
    private val fetchPlacesResult: Result<FeatureCollection> = Result.success(FeatureCollection(features = emptyList())),
    private val fetchPlaceResult: Result<Feature> = Result.failure(IllegalStateException("missing")),
    private val createResult: Result<Feature> = Result.success(
        Feature(geometry = Geometry(coordinates = listOf(0.0, 0.0)), properties = Properties(name = "x")),
    ),
    private val updateResult: Result<Feature> = Result.success(
        Feature(geometry = Geometry(coordinates = listOf(0.0, 0.0)), properties = Properties(name = "x")),
    ),
) : PlacesRemoteDataSource {
    val createCalls = mutableListOf<Feature>()
    override suspend fun fetchPlacesCancellable(): Result<FeatureCollection> = fetchPlacesResult
    override suspend fun fetchPlace(id: Int): Result<Feature> = fetchPlaceResult
    override suspend fun createPlace(feature: Feature): Result<Feature> {
        createCalls.add(feature)
        return createResult
    }
    override suspend fun updatePlace(id: Int, feature: Feature): Result<Feature> = updateResult
}

private class SequencedRepo(
    private val fetchPlaceResults: MutableList<Result<Feature>>,
    private val createResults: MutableList<Result<Feature>>,
    private val updateResults: MutableList<Result<Feature>>,
) : PlacesRemoteDataSource {
    override suspend fun fetchPlacesCancellable(): Result<FeatureCollection> {
        return Result.success(FeatureCollection(features = emptyList()))
    }

    override suspend fun fetchPlace(id: Int): Result<Feature> = fetchPlaceResults.removeFirst()

    override suspend fun createPlace(feature: Feature): Result<Feature> = createResults.removeFirst()

    override suspend fun updatePlace(id: Int, feature: Feature): Result<Feature> = updateResults.removeFirst()
}
