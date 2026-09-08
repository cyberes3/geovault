package com.geovault.places.domain

import com.geovault.places.model.PendingChange
import com.geovault.places.model.PlaceKey
import com.geovault.places.samplePlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacesListProjectionTest {
    @Test
    fun searchFiltersMergedList_pendingEditDoesNotRevealStaleName() {
        val pendingEdit = samplePlace(
            key = PlaceKey.server(7),
            serverId = 7,
            name = "Updated Camp",
            pending = PendingChange.Update(samplePlace(name = "Cached Camp").content),
        )

        val matching = PlacesListProjection.filter(listOf(pendingEdit), "Updated")
        assertEquals(listOf("Updated Camp"), matching.waitingToSync.map { it.content.name })
        assertTrue(matching.saved.isEmpty())

        val staleQuery = PlacesListProjection.filter(listOf(pendingEdit), "Cached")
        assertTrue(staleQuery.waitingToSync.isEmpty())
        assertTrue(staleQuery.saved.isEmpty())
    }

    @Test
    fun pendingDeletesAreHiddenFromListAndExport() {
        val live = samplePlace(key = PlaceKey.server(1), serverId = 1, name = "Keep")
        val tombstone = samplePlace(
            key = PlaceKey.server(2),
            serverId = 2,
            name = "Gone",
            pending = PendingChange.Delete(samplePlace(name = "Gone").content),
        )

        val sections = PlacesListProjection.filter(listOf(live, tombstone), "")
        assertEquals(listOf("Keep"), sections.saved.map { it.content.name })
        assertTrue(sections.waitingToSync.isEmpty())
        assertEquals(listOf("Keep"), PlacesListProjection.exportable(listOf(live, tombstone)).map { it.content.name })
    }

    @Test
    fun waitingToSyncExcludesPendingDeletes() {
        val draft = samplePlace(
            key = PlaceKey.local("draft"),
            serverId = null,
            name = "Draft",
            pending = PendingChange.Create,
        )
        val sections = PlacesListProjection.filter(listOf(draft), "")
        assertEquals(listOf("Draft"), sections.waitingToSync.map { it.content.name })
        assertTrue(sections.saved.isEmpty())
    }
}
