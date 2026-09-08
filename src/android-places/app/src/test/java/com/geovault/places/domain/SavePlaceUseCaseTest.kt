package com.geovault.places.domain

import com.geovault.common.net.GeoVaultApiFailure
import com.geovault.places.FakePlacesRemote
import com.geovault.places.InMemoryPlacesStore
import com.geovault.places.model.PendingChange
import com.geovault.places.model.PlaceKey
import com.geovault.places.presentation.PlacesOfflineBehaviorPolicy
import com.geovault.places.samplePlace
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavePlaceUseCaseTest {
    @Test
    fun saveOnlineCreateCommitsServerPlace() = runTest {
        val draft = samplePlace(
            key = PlaceKey.local("new"),
            serverId = null,
            name = "Draft",
            pending = PendingChange.Create,
        )
        val server = draft.syncedFromServer(11, "2026-02-02")
        val store = InMemoryPlacesStore(listOf(draft))
        val remote = FakePlacesRemote(onCreatePlace = { server })
        val useCase = SavePlaceUseCase(remote, store)

        val outcome = useCase.save(draft, baseline = null)

        assertTrue(outcome is SavePlaceOutcome.SavedOnline)
        assertEquals(listOf(PlaceKey.server(11)), store.places().map { it.key })
        assertEquals(11, store.places().single().serverId)
    }

    @Test
    fun saveOnlineUpdateCommitsServerPlace() = runTest {
        val place = samplePlace(name = "Edited")
        val store = InMemoryPlacesStore(listOf(place))
        val remote = FakePlacesRemote(onUpdatePlace = { _, updated -> updated.copy(pending = null) })
        val useCase = SavePlaceUseCase(remote, store)

        val outcome = useCase.save(place, baseline = place.content)

        assertTrue(outcome is SavePlaceOutcome.SavedOnline)
        assertEquals("Edited", store.places().single().content.name)
    }

    @Test
    fun retryableFailureQueuesCreate() = runTest {
        val draft = samplePlace(key = PlaceKey.local("n"), serverId = null, name = "Draft")
        val store = InMemoryPlacesStore()
        val remote = FakePlacesRemote(
            onCreatePlace = { throw GeoVaultApiFailure(httpCode = 503, serverMessage = "down") },
        )
        val useCase = SavePlaceUseCase(remote, store)

        val outcome = useCase.save(draft, baseline = null)

        assertTrue(outcome is SavePlaceOutcome.QueuedOffline)
        assertEquals(PlacesOfflineBehaviorPolicy.SAVED_OFFLINE_NETWORK_MESSAGE, (outcome as SavePlaceOutcome.QueuedOffline).message)
        assertEquals(PendingChange.Create, store.places().single().pending)
    }

    @Test
    fun authFailureDoesNotQueue() = runTest {
        val place = samplePlace()
        val store = InMemoryPlacesStore()
        val remote = FakePlacesRemote(
            onUpdatePlace = { _, _ -> throw GeoVaultApiFailure(httpCode = 401, serverMessage = "nope") },
        )
        val useCase = SavePlaceUseCase(remote, store)

        val outcome = useCase.save(place, baseline = place.content)

        assertTrue(outcome is SavePlaceOutcome.AuthRequired)
        assertTrue(store.places().isEmpty())
    }

    @Test
    fun validationFailureDoesNotQueue() = runTest {
        val place = samplePlace()
        val store = InMemoryPlacesStore()
        val remote = FakePlacesRemote(
            onUpdatePlace = { _, _ -> throw GeoVaultApiFailure(httpCode = 400, serverMessage = "bad name") },
        )
        val useCase = SavePlaceUseCase(remote, store)

        val outcome = useCase.save(place, baseline = place.content)

        assertTrue(outcome is SavePlaceOutcome.FailedValidation)
        assertEquals("bad name", (outcome as SavePlaceOutcome.FailedValidation).message)
        assertTrue(store.places().isEmpty())
    }

    @Test
    fun conflictQueuesOfflineCopy() = runTest {
        val draft = samplePlace(key = PlaceKey.local("dup"), serverId = null, name = "Camp")
        val store = InMemoryPlacesStore()
        val remote = FakePlacesRemote(
            onCreatePlace = { throw GeoVaultApiFailure(httpCode = 409, serverMessage = "exists") },
        )
        val useCase = SavePlaceUseCase(remote, store)

        val outcome = useCase.save(draft, baseline = null)

        assertTrue(outcome is SavePlaceOutcome.QueuedOffline)
        assertEquals(PendingChange.Create, store.places().single().pending)
    }
}
