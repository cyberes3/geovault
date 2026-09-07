package com.geovault.tracker.presentation

import com.geovault.common.net.GeoVaultApiFailure
import com.geovault.tracker.MapVisibilityRequest
import com.geovault.tracker.MapVisibilityResponse
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
                MapVisibilityResponse()
            },
            patchVisibility = { request ->
                patchedRequest = request
                MapVisibilityResponse(hidden_track_ids = request.hidden_track_ids.orEmpty())
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
                MapVisibilityResponse(hidden_group_ids = listOf("g0"))
            },
            patchVisibility = { request ->
                MapVisibilityResponse(
                    hidden_group_ids = request.hidden_group_ids.orEmpty()
                )
            }
        )

        assertEquals(1, loadCallCount)
        val success = result as MapVisibilityMutationResult.Success
        assertEquals(listOf("g0", "g1"), success.visibility.hidden_group_ids.orEmpty().sorted())
    }

    @Test
    fun toggle_returnsFailureWhenLoadFails() = runBlocking {
        val network = GeoVaultApiFailure(httpCode = null, serverMessage = "network")
        val result = MapVisibilityMutationCoordinator.toggle(
            current = null,
            target = MapVisibilityToggleTarget(id = "t1", type = MapVisibilityToggleEntityType.Tracker),
            loadVisibility = { throw network },
            patchVisibility = { MapVisibilityResponse() }
        )

        assertEquals(MapVisibilityMutationResult.Failure(network), result)
    }

    @Test
    fun toggle_returnsFailureWhenPatchFails() = runBlocking {
        val unauthorized = GeoVaultApiFailure(httpCode = 401, serverMessage = "unauthorized")
        val result = MapVisibilityMutationCoordinator.toggle(
            current = MapVisibilityResponse(),
            target = MapVisibilityToggleTarget(id = "t1", type = MapVisibilityToggleEntityType.Tracker),
            loadVisibility = { MapVisibilityResponse() },
            patchVisibility = { throw unauthorized }
        )

        assertEquals(MapVisibilityMutationResult.Failure(unauthorized), result)
    }
}
