package com.geovault.tracker.presentation

import com.geovault.tracker.AvailableToAddGroup
import com.geovault.tracker.AvailableToAddItem
import com.geovault.tracker.AvailableToAddResponse
import com.geovault.tracker.Group
import com.geovault.tracker.Tracker
import org.junit.Assert.assertEquals
import org.junit.Test

class SharedDiscoveryPolicyTest {

    @Test
    fun derive_filtersIncomingThatAlreadyExistLocally() {
        val buckets = SharedDiscoveryPolicy.derive(
            availableToAdd = AvailableToAddResponse(
                shared_with_me = listOf(
                    AvailableToAddItem(id = "t1", name = "Already on map"),
                    AvailableToAddItem(id = "t2", name = "Incoming")
                )
            ),
            trackers = listOf(
                Tracker(id = "t1", name = "Owned", color = null)
            ),
            groups = emptyList()
        )

        assertEquals(listOf("t2"), buckets.incomingTrackers.map { it.id })
    }

    @Test
    fun derive_dedupesPublicAgainstIncomingAndExisting() {
        val buckets = SharedDiscoveryPolicy.derive(
            availableToAdd = AvailableToAddResponse(
                shared_with_me = listOf(AvailableToAddItem(id = "a", name = "Incoming A")),
                public = listOf(
                    AvailableToAddItem(id = "a", name = "Public duplicate"),
                    AvailableToAddItem(id = "b", name = "Public B"),
                    AvailableToAddItem(id = "c", name = "Public C")
                )
            ),
            trackers = listOf(
                Tracker(id = "c", name = "Existing C", color = null)
            ),
            groups = emptyList()
        )

        assertEquals(listOf("b"), buckets.publicTrackers.map { it.id })
    }

    @Test
    fun derive_filtersPublicGroupTrackIdsAlreadyOnMap() {
        val buckets = SharedDiscoveryPolicy.derive(
            availableToAdd = AvailableToAddResponse(
                public_groups = listOf(
                    AvailableToAddGroup(id = "g1", name = "G", track_ids = listOf("t1", "t2", "t2"))
                )
            ),
            trackers = listOf(
                Tracker(id = "t1", name = "Existing", color = null)
            ),
            groups = emptyList()
        )

        assertEquals(listOf("g1"), buckets.publicGroups.map { it.id })
        assertEquals(listOf("t2"), buckets.publicGroups.first().track_ids)
    }

    @Test
    fun derive_filtersIncomingAndPublicGroupsThatAlreadyExistLocally() {
        val buckets = SharedDiscoveryPolicy.derive(
            availableToAdd = AvailableToAddResponse(
                shared_with_me_groups = listOf(
                    AvailableToAddGroup(id = "g1", name = "Known incoming"),
                    AvailableToAddGroup(id = "g2", name = "Incoming new"),
                ),
                public_groups = listOf(
                    AvailableToAddGroup(id = "g2", name = "Public duplicate"),
                    AvailableToAddGroup(id = "g3", name = "Public new"),
                )
            ),
            trackers = emptyList(),
            groups = listOf(
                Group(id = "g1", name = "Known local")
            )
        )

        assertEquals(listOf("g2"), buckets.incomingGroups.map { it.id })
        assertEquals(listOf("g3"), buckets.publicGroups.map { it.id })
    }

    @Test
    fun derive_incomingGroupsNeverExposeTrackIdsBeforeAccept() {
        val buckets = SharedDiscoveryPolicy.derive(
            availableToAdd = AvailableToAddResponse(
                shared_with_me_groups = listOf(
                    AvailableToAddGroup(id = "g1", name = "Pending Group", track_ids = listOf("t1", "t2"))
                )
            ),
            trackers = emptyList(),
            groups = emptyList()
        )

        assertEquals(listOf("g1"), buckets.incomingGroups.map { it.id })
        assertEquals(emptyList<String>(), buckets.incomingGroups.first().track_ids)
    }
}
