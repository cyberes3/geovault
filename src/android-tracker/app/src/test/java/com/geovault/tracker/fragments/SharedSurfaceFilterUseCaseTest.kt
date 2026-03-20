package com.geovault.tracker.fragments

import com.geovault.tracker.Group
import com.geovault.tracker.Tracker
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SharedSurfaceFilterUseCaseTest {
    private val useCase = SharedSurfaceFilterUseCase()

    @Test
    fun filter_returnsAcceptedVisibleSharedGroupsAndUngroupedSharedTrackers() {
        val groups = listOf(
            Group(id = "g-accepted", name = "Accepted", visibility = "shared", is_owner = false, is_accepted = true, track_ids = listOf("t-grouped")),
            Group(id = "g-pending", name = "Pending", visibility = "shared", is_owner = false, is_accepted = false, track_ids = listOf("t-pending"))
        )
        val trackers = listOf(
            Tracker(id = "t-grouped", name = "Grouped", color = null, is_owner = false, visibility = "shared"),
            Tracker(id = "t-standalone", name = "Standalone", color = null, is_owner = false, visibility = "public"),
            Tracker(id = "t-hidden", name = "Hidden", color = null, is_owner = false, visibility = "shared")
        )
        val result = useCase.filter(
            groups = groups,
            trackers = trackers,
            hiddenTrackIds = setOf("t-hidden"),
            hiddenGroupIds = emptySet()
        )
        assertEquals(listOf("g-accepted"), result.sharedGroups.map { it.id })
        assertEquals(listOf("t-standalone"), result.sharedTrackers.map { it.id })
    }
}
