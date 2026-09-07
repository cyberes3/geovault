package com.geovault.tracker.presentation

import com.geovault.common.net.GeoVaultApiFailure
import com.geovault.tracker.Group
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.Tracker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HiddenMapItemsCoordinatorTest {

    @Test
    fun loadSnapshot_failsWhenMapVisibilityFails() = runBlocking {
        val network = GeoVaultApiFailure(httpCode = null, serverMessage = "network")
        try {
            HiddenMapItemsCoordinator.loadSnapshot(
                forceRefresh = true,
                loadMapVisibility = { throw network },
                loadTrackers = { emptyList() },
                loadGroups = { emptyList() }
            )
            fail("expected GeoVaultApiFailure")
        } catch (e: GeoVaultApiFailure) {
            assertEquals(network, e)
        }
    }

    @Test
    fun loadSnapshot_returnsWarningWhenTrackersOrGroupsFail() = runBlocking {
        val unauthorized = GeoVaultApiFailure(httpCode = 401, serverMessage = "unauthorized")
        val snapshot = HiddenMapItemsCoordinator.loadSnapshot(
            forceRefresh = false,
            loadMapVisibility = {
                MapVisibilityResponse(hidden_track_ids = listOf("t1"))
            },
            loadTrackers = { throw unauthorized },
            loadGroups = { emptyList() }
        )

        assertEquals(unauthorized, snapshot.warning)
        assertEquals(emptyList<Tracker>(), snapshot.trackers)
        assertEquals(emptyList<Group>(), snapshot.groups)
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
