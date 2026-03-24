package com.geovault.tracker.data

import com.geovault.tracker.GeoJsonLineString
import com.geovault.tracker.Group
import com.geovault.tracker.Tracker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultGroupTrackerEligibilityUseCaseTest {
    private val useCase = DefaultGroupTrackerEligibilityUseCase()

    @Test
    fun addableTrackers_excludesTrackersAlreadyInGroup() {
        val group = group(trackIds = listOf("t1"))
        val tracker = tracker(id = "t1", owner = true)

        val result = useCase.addableTrackers(listOf(tracker), group)

        assertFalse(result.first().canAdd)
    }

    @Test
    fun addableTrackers_ownerHidden_isNotAddable() {
        val group = group(trackIds = emptyList())
        val tracker = tracker(id = "t1", owner = true, hidden = true)

        val result = useCase.addableTrackers(listOf(tracker), group)

        assertFalse(result.first().canAdd)
    }

    @Test
    fun addableTrackers_sharedAllowReshareAndPublic_isAddable() {
        val group = group(trackIds = emptyList())
        val tracker = tracker(
            id = "t1",
            owner = false,
            allowReshare = true,
            visibility = "public"
        )

        val result = useCase.addableTrackers(listOf(tracker), group)

        assertTrue(result.first().canAdd)
    }

    @Test
    fun addableTrackers_sharedWithoutAllowReshare_isNotAddable() {
        val group = group(trackIds = emptyList())
        val tracker = tracker(
            id = "t1",
            owner = false,
            allowReshare = false,
            visibility = "public"
        )

        val result = useCase.addableTrackers(listOf(tracker), group)

        assertFalse(result.first().canAdd)
    }

    @Test
    fun addableTrackers_sharedAllowReshareButPrivate_isNotAddable() {
        val group = group(trackIds = emptyList())
        val tracker = tracker(
            id = "t1",
            owner = false,
            allowReshare = true,
            visibility = "shared"
        )

        val result = useCase.addableTrackers(listOf(tracker), group)

        assertFalse(result.first().canAdd)
    }

    private fun group(trackIds: List<String>): Group = Group(
        id = "g1",
        name = "Group One",
        visibility = "shared",
        is_owner = true,
        is_accepted = true,
        track_ids = trackIds
    )

    private fun tracker(
        id: String,
        owner: Boolean,
        allowReshare: Boolean = false,
        hidden: Boolean = false,
        visibility: String = "private"
    ): Tracker = Tracker(
        id = id,
        name = id,
        color = null,
        settings = mapOf(
            "allow_group_reshare" to allowReshare,
            "hidden" to hidden
        ),
        geometry = GeoJsonLineString(type = "LineString", coordinates = emptyList()),
        point_params = emptyList(),
        is_owner = owner,
        visibility = visibility
    )
}
