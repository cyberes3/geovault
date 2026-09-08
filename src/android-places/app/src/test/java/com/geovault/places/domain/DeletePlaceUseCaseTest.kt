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

class DeletePlaceUseCaseTest {
    @Test
    fun deleteOnlineRemovesRow() = runTest {
        val place = samplePlace()
        val store = InMemoryPlacesStore(listOf(place))
        val remote = FakePlacesRemote(onDeletePlace = {})
        val useCase = DeletePlaceUseCase(remote, store)

        val outcome = useCase.deleteOrRevert(place)

        assertEquals(DeletePlaceOutcome.DeletedOnline, outcome)
        assertTrue(store.places().isEmpty())
        assertEquals(listOf(1), remote.deleted)
    }

    @Test
    fun discardPendingCreateIsLocalOnly() = runTest {
        val draft = samplePlace(
            key = PlaceKey.local("n"),
            serverId = null,
            pending = PendingChange.Create,
        )
        val store = InMemoryPlacesStore(listOf(draft))
        val remote = FakePlacesRemote()
        val useCase = DeletePlaceUseCase(remote, store)

        val outcome = useCase.deleteOrRevert(draft)

        assertEquals(DeletePlaceOutcome.DiscardedLocal, outcome)
        assertTrue(store.places().isEmpty())
        assertTrue(remote.deleted.isEmpty())
    }

    @Test
    fun revertPendingUpdateRestoresBaseline() = runTest {
        val baseline = samplePlace(name = "Original").content
        val edited = samplePlace(name = "Edited", pending = PendingChange.Update(baseline))
        val store = InMemoryPlacesStore(listOf(edited))
        val useCase = DeletePlaceUseCase(FakePlacesRemote(), store)

        val outcome = useCase.deleteOrRevert(edited)

        assertTrue(outcome is DeletePlaceOutcome.Reverted)
        assertEquals("Original", store.places().single().content.name)
        assertEquals(null, store.places().single().pending)
    }

    @Test
    fun retryableDeleteQueuesTombstone() = runTest {
        val place = samplePlace()
        val store = InMemoryPlacesStore(listOf(place))
        val remote = FakePlacesRemote(
            onDeletePlace = { throw GeoVaultApiFailure(httpCode = 503, serverMessage = "down") },
        )
        val useCase = DeletePlaceUseCase(remote, store)

        val outcome = useCase.deleteOrRevert(place)

        assertTrue(outcome is DeletePlaceOutcome.QueuedOffline)
        assertEquals(
            PlacesOfflineBehaviorPolicy.DELETE_QUEUED_OFFLINE_MESSAGE,
            (outcome as DeletePlaceOutcome.QueuedOffline).message,
        )
        assertTrue(store.places().single().pending is PendingChange.Delete)
    }

    @Test
    fun authFailureDoesNotQueueDelete() = runTest {
        val place = samplePlace()
        val store = InMemoryPlacesStore(listOf(place))
        val remote = FakePlacesRemote(
            onDeletePlace = { throw GeoVaultApiFailure(httpCode = 401, serverMessage = "nope") },
        )
        val useCase = DeletePlaceUseCase(remote, store)

        val outcome = useCase.deleteOrRevert(place)

        assertTrue(outcome is DeletePlaceOutcome.AuthRequired)
        assertEquals(null, store.places().single().pending)
    }
}
