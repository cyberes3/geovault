package com.geovault.places.domain

import com.geovault.common.net.GeoVaultApiFailure
import com.geovault.places.FakePlacesRemote
import com.geovault.places.InMemoryPlacesStore
import com.geovault.places.model.PendingChange
import com.geovault.places.model.PlaceKey
import com.geovault.places.samplePlace
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacesSyncEngineTest {
    @Test
    fun syncPushesPendingThenReplacesNonPendingFromOneFetch() = runTest {
        val draft = samplePlace(
            key = PlaceKey.local("n"),
            serverId = null,
            name = "Draft",
            pending = PendingChange.Create,
        )
        val store = InMemoryPlacesStore(listOf(draft))
        val created = draft.syncedFromServer(21, "2026-03-03")
        val remote = FakePlacesRemote(
            onCreatePlace = { created },
            onFetchPlaces = { listOf(created, samplePlace(key = PlaceKey.server(22), serverId = 22, name = "Other")) },
        )
        val engine = PlacesSyncEngine(remote, store, ConflictResolutionPolicy(), NoopNavFlusher)

        val report = engine.sync()

        assertTrue(report.hadQueuedItems)
        assertEquals(1, report.successCount)
        assertEquals(0, report.failedCount)
        assertEquals(setOf(21, 22), store.places().mapNotNull { it.serverId }.toSet())
        assertTrue(store.places().none { it.pending != null })
    }

    @Test
    fun syncKeepsPendingRowsWhenSnapshotArrives() = runTest {
        val pending = samplePlace(
            name = "Local Edit",
            pending = PendingChange.Update(samplePlace(name = "Baseline").content),
        )
        val store = InMemoryPlacesStore(listOf(pending))
        val remote = FakePlacesRemote(
            onFetchPlace = { samplePlace(name = "Baseline") },
            onUpdatePlace = { _, _ -> throw GeoVaultApiFailure(httpCode = 503, serverMessage = "down") },
            onFetchPlaces = { listOf(samplePlace(name = "Server Edit")) },
        )
        val engine = PlacesSyncEngine(remote, store, ConflictResolutionPolicy(), NoopNavFlusher)

        val report = engine.sync()

        assertEquals(1, report.failedCount)
        assertEquals("Local Edit", store.places().first { it.serverId == 1 }.content.name)
        assertTrue(store.places().first { it.serverId == 1 }.pending is PendingChange.Update)
    }

    @Test
    fun createAndUpdateConflictBuildUniqueCopy() = runTest {
        val local = samplePlace(
            key = PlaceKey.local("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
            serverId = null,
            name = "Camp",
            pending = PendingChange.Create,
        )
        val store = InMemoryPlacesStore(listOf(local))
        val copyHolder = mutableListOf<String>()
        val remote = FakePlacesRemote(
            onCreatePlace = { place ->
                if (place.content.name.contains("conflict")) {
                    copyHolder += place.content.name
                    place.syncedFromServer(90, null)
                } else {
                    throw GeoVaultApiFailure(httpCode = 409, serverMessage = "exists")
                }
            },
            onFetchPlaces = { emptyList() },
        )
        val engine = PlacesSyncEngine(remote, store, ConflictResolutionPolicy(), NoopNavFlusher)

        val report = engine.sync()

        assertEquals(1, report.conflictCount)
        assertTrue(copyHolder.single().startsWith("Camp (conflict "))
        assertFalse(copyHolder.single().contains("Conflicted"))
    }

    @Test
    fun http400DoesNotDropQueuedUpdate() = runTest {
        val pending = samplePlace(
            name = "Local Edit",
            pending = PendingChange.Update(samplePlace(name = "Baseline").content),
        )
        val store = InMemoryPlacesStore(listOf(pending))
        val remote = FakePlacesRemote(
            onFetchPlace = { samplePlace(name = "Baseline") },
            onUpdatePlace = { _, _ -> throw GeoVaultApiFailure(httpCode = 400, serverMessage = "invalid field") },
            onFetchPlaces = { listOf(samplePlace(name = "Server")) },
        )
        val engine = PlacesSyncEngine(remote, store, ConflictResolutionPolicy(), NoopNavFlusher)

        val report = engine.sync()

        assertEquals(1, report.failedCount)
        assertEquals("invalid field", report.items.single().message)
        val kept = store.places().first { it.serverId == 1 }
        assertEquals("Local Edit", kept.content.name)
        assertTrue(kept.pending is PendingChange.Update)
    }

    @Test
    fun deleteTreats404AsSuccess() = runTest {
        val tombstone = samplePlace(pending = PendingChange.Delete(samplePlace().content))
        val store = InMemoryPlacesStore(listOf(tombstone))
        val remote = FakePlacesRemote(
            onDeletePlace = { throw GeoVaultApiFailure(httpCode = 404, serverMessage = "gone") },
            onFetchPlaces = { emptyList() },
        )
        val engine = PlacesSyncEngine(remote, store, ConflictResolutionPolicy(), NoopNavFlusher)

        val report = engine.sync()

        assertEquals(1, report.successCount)
        assertTrue(store.places().isEmpty())
    }
}

private object NoopNavFlusher : NavigationRetryFlusher {
    override suspend fun flushPending() = Unit
}
