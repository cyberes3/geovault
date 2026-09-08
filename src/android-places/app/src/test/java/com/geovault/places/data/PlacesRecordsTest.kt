package com.geovault.places.data

import com.geovault.places.model.PendingChange
import com.geovault.places.model.PlaceKey
import com.geovault.places.samplePlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlacesRecordsTest {
    @Test
    fun upsertReplacesSameKeyAtomically() {
        val original = samplePlace(name = "Old")
        val updated = original.copy(content = original.content.copy(name = "New"))

        val result = PlacesRecords.upsert(listOf(original), updated)

        assertEquals(listOf("New"), result.map { it.content.name })
        assertEquals(1, result.size)
    }

    @Test
    fun commitSyncedReplacesLocalKeyWithServerKey() {
        val localKey = PlaceKey.local("draft-1")
        val draft = samplePlace(
            key = localKey,
            serverId = null,
            name = "Draft",
            pending = PendingChange.Create,
        )
        val server = samplePlace(key = PlaceKey.server(44), serverId = 44, name = "Draft")

        val result = PlacesRecords.commitSynced(listOf(draft), localKey, server)

        assertEquals(1, result.size)
        assertEquals(PlaceKey.server(44), result.single().key)
        assertEquals(44, result.single().serverId)
        assertNull(result.single().pending)
        assertEquals(0, result.count { it.key == localKey })
    }

    @Test
    fun applyServerSnapshotKeepsPendingAndReplacesSynced() {
        val pending = samplePlace(
            key = PlaceKey.server(7),
            serverId = 7,
            name = "Local Edit",
            pending = PendingChange.Update(samplePlace(name = "Baseline").content),
        )
        val stale = samplePlace(key = PlaceKey.server(8), serverId = 8, name = "Stale")
        val serverPending = samplePlace(key = PlaceKey.server(7), serverId = 7, name = "Server Edit")
        val serverFresh = samplePlace(key = PlaceKey.server(9), serverId = 9, name = "Fresh")

        val result = PlacesRecords.applyServerSnapshot(
            local = listOf(pending, stale),
            server = listOf(serverPending, serverFresh),
        )

        assertEquals(listOf("Local Edit", "Fresh"), result.map { it.content.name })
        assertEquals(PendingChange.Update(samplePlace(name = "Baseline").content)::class, result.first().pending!!::class)
    }
}
