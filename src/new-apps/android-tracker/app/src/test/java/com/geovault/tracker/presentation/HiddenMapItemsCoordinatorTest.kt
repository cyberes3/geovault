package com.geovault.tracker.presentation

import com.geovault.tracker.AppError
import com.geovault.tracker.Group
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenMapItemsCoordinatorTest {

    @Test
    fun loadSnapshot_failsWhenMapVisibilityFails() = runBlocking {
        val result = HiddenMapItemsCoordinator.loadSnapshot(
            forceRefresh = true,
            loadMapVisibility = { RepositoryResult.Failure(AppError.Network) },
            loadTrackers = { RepositoryResult.Success(emptyList()) },
            loadGroups = { RepositoryResult.Success(emptyList()) }
        )

        assertEquals(RepositoryResult.Failure(AppError.Network), result)
    }

    @Test
    fun loadSnapshot_returnsWarningWhenTrackersOrGroupsFail() = runBlocking {
        val result = HiddenMapItemsCoordinator.loadSnapshot(
            forceRefresh = false,
            loadMapVisibility = {
                RepositoryResult.Success(MapVisibilityResponse(hidden_track_ids = listOf("t1")))
            },
            loadTrackers = { RepositoryResult.Failure(AppError.Unauthorized) },
            loadGroups = { RepositoryResult.Success(emptyList()) }
        )

        val success = result as RepositoryResult.Success
        assertEquals(AppError.Unauthorized, success.data.warning)
        assertEquals(emptyList<Tracker>(), success.data.trackers)
        assertEquals(emptyList<Group>(), success.data.groups)
    }

    @Test
    fun buildUnhideItemRequest_removesOnlyTargetedId() {
        val visibility = MapVisibilityResponse(
            hidden_track_ids = listOf("t1", "t2"),
            hidden_group_ids = listOf("g1", "g2")
        )

        val unhideTracker = HiddenMapItemsCoordinator.buildUnhideItemRequest(
            mapVisibility = visibility,
            item = HiddenMapItem(id = "t1", name = "Tracker", type = HiddenMapItemType.TRACKER)
        )
        assertEquals(listOf("t2"), unhideTracker.hidden_track_ids)
        assertEquals(listOf("g1", "g2"), unhideTracker.hidden_group_ids)

        val unhideGroup = HiddenMapItemsCoordinator.buildUnhideItemRequest(
            mapVisibility = visibility,
            item = HiddenMapItem(id = "g2", name = "Group", type = HiddenMapItemType.GROUP)
        )
        assertEquals(listOf("t1", "t2"), unhideGroup.hidden_track_ids)
        assertEquals(listOf("g1"), unhideGroup.hidden_group_ids)
    }

    @Test
    fun buildUnhideAllRequest_clearsBothLists() {
        val request = HiddenMapItemsCoordinator.buildUnhideAllRequest()
        assertTrue(request.hidden_track_ids.isNullOrEmpty())
        assertTrue(request.hidden_group_ids.isNullOrEmpty())
    }
}
