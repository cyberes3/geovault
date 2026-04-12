package com.geovault.tracker.data

import com.geovault.tracker.Tracker
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackerManagementStateStoreTest {

    @Test
    fun publishTrackers_appliesNaturalSortAndStableTieBreaker() {
        val store = TrackerManagementStateStore()

        store.publishTrackers(
            listOf(
                Tracker(id = "b-id", name = "Alpha", color = null),
                Tracker(id = "tracker-10", name = "Tracker 10", color = null),
                Tracker(id = "tracker-2", name = "Tracker 2", color = null),
                Tracker(id = "a-id", name = "Alpha", color = null),
                Tracker(id = "tracker-1", name = "Tracker 1", color = null),
            )
        )

        assertEquals(
            listOf("a-id", "b-id", "tracker-1", "tracker-2", "tracker-10"),
            store.trackers.value.map { it.id }
        )
    }

    @Test
    fun publishTracker_keepsCanonicalOrderAfterUpsert() {
        val store = TrackerManagementStateStore()
        store.publishTrackers(
            listOf(
                Tracker(id = "t1", name = "Tracker 1", color = null),
                Tracker(id = "t3", name = "Tracker 3", color = null),
            )
        )

        store.publishTracker(Tracker(id = "t2", name = "Tracker 2", color = null))

        assertEquals(
            listOf("t1", "t2", "t3"),
            store.trackers.value.map { it.id }
        )
    }
}
