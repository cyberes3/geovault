package com.geovault.tracker.presentation

import com.geovault.tracker.AppError
import com.geovault.tracker.MapVisibilityRequest
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.RepositoryResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapVisibilityMutationCoordinatorTest {

    @Test
    fun toggle_usesCurrentStateWithoutLoading() = runBlocking {
        var loadCallCount = 0
        var patchedRequest: MapVisibilityRequest? = null
        val current = MapVisibilityResponse(hidden_track_ids = listOf("a"), hidden_group_ids = emptyList())

        val result = MapVisibilityMutationCoordinator.toggle(
            current = current,
            target = MapVisibilityToggleTarget(id = "b", type = MapVisibilityToggleEntityType.Tracker),
            loadVisibility = {
                loadCallCount += 1
                RepositoryResult.Success(MapVisibilityResponse())
            },
            patchVisibility = { request ->
                patchedRequest = request
                RepositoryResult.Success(MapVisibilityResponse(hidden_track_ids = request.hidden_track_ids.orEmpty()))
            }
        )

        assertEquals(0, loadCallCount)
        assertTrue((patchedRequest?.hidden_track_ids ?: emptyList()).containsAll(listOf("a", "b")))
        assertTrue(result is MapVisibilityMutationResult.Success)
    }

    @Test
    fun toggle_loadsWhenCurrentMissing() = runBlocking {
        var loadCallCount = 0

        val result = MapVisibilityMutationCoordinator.toggle(
            current = null,
            target = MapVisibilityToggleTarget(id = "g1", type = MapVisibilityToggleEntityType.Group),
            loadVisibility = {
                loadCallCount += 1
                RepositoryResult.Success(MapVisibilityResponse(hidden_group_ids = listOf("g0")))
            },
            patchVisibility = { request ->
                RepositoryResult.Success(
                    MapVisibilityResponse(
                        hidden_group_ids = request.hidden_group_ids.orEmpty()
                    )
                )
            }
        )

        assertEquals(1, loadCallCount)
        val success = result as MapVisibilityMutationResult.Success
        assertEquals(listOf("g0", "g1"), success.visibility.hidden_group_ids.orEmpty().sorted())
    }

    @Test
    fun toggle_returnsFailureWhenLoadFails() = runBlocking {
        val result = MapVisibilityMutationCoordinator.toggle(
            current = null,
            target = MapVisibilityToggleTarget(id = "t1", type = MapVisibilityToggleEntityType.Tracker),
            loadVisibility = { RepositoryResult.Failure(AppError.Network) },
            patchVisibility = { RepositoryResult.Success(MapVisibilityResponse()) }
        )

        assertEquals(MapVisibilityMutationResult.Failure(AppError.Network), result)
    }

    @Test
    fun toggle_returnsFailureWhenPatchFails() = runBlocking {
        val result = MapVisibilityMutationCoordinator.toggle(
            current = MapVisibilityResponse(),
            target = MapVisibilityToggleTarget(id = "t1", type = MapVisibilityToggleEntityType.Tracker),
            loadVisibility = { RepositoryResult.Success(MapVisibilityResponse()) },
            patchVisibility = { RepositoryResult.Failure(AppError.Unauthorized) }
        )

        assertEquals(MapVisibilityMutationResult.Failure(AppError.Unauthorized), result)
    }
}
