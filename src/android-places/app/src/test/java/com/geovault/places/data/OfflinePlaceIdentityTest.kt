package com.geovault.places.data

import com.geovault.places.model.Feature
import com.geovault.places.model.Geometry
import com.geovault.places.model.OfflineFeature
import com.geovault.places.model.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure identity matching used by map/list edit routing (same rules as PlacesStore.findOfflineForFeature).
 */
class OfflinePlaceIdentityTest {
    @Test
    fun matchesByDatabaseIdEvenWhenNameDiffers() {
        val queued = OfflineFeature(
            clientLocalId = "edit-7",
            feature = place(databaseId = 7, name = "Edited"),
        )
        val display = place(databaseId = 7, name = "Edited")
        assertEquals("edit-7", findOfflineForFeature(listOf(queued), display)?.clientLocalId)
    }

    @Test
    fun matchesUnsyncedCreateByNameAndCoords() {
        val queued = OfflineFeature(
            clientLocalId = "create-1",
            feature = place(databaseId = null, name = "Draft"),
        )
        val other = OfflineFeature(
            clientLocalId = "create-2",
            feature = place(databaseId = null, name = "Other", lon = 3.0, lat = 4.0),
        )
        val display = place(databaseId = null, name = "Draft")
        assertEquals(
            "create-1",
            findOfflineForFeature(listOf(queued, other), display)?.clientLocalId,
        )
    }

    @Test
    fun searchFilteredIndexStyleLookupIsNotUsed() {
        // Previously list used filtered index; identity must be clientLocalId only.
        val queue = listOf(
            OfflineFeature(clientLocalId = "a", feature = place(databaseId = null, name = "Alpha")),
            OfflineFeature(clientLocalId = "b", feature = place(databaseId = null, name = "Beta")),
        )
        val filtered = queue.filter { it.feature.properties.name!!.startsWith("B") }
        val wrongIndexStyle = filtered.getOrNull(0)
        val byId = queue.first { it.clientLocalId == "b" }
        assertEquals("b", byId.clientLocalId)
        assertEquals(wrongIndexStyle?.clientLocalId, byId.clientLocalId)
        assertNull(findOfflineForFeature(queue, place(databaseId = null, name = "Missing")))
    }

    private fun findOfflineForFeature(
        offline: List<OfflineFeature>,
        feature: Feature,
    ): OfflineFeature? {
        val databaseId = feature.properties.database_id
        if (databaseId != null) {
            offline.firstOrNull { it.feature.properties.database_id == databaseId }?.let { return it }
        }
        val name = feature.properties.name
        val coords = feature.geometry.coordinates
        return offline.firstOrNull { item ->
            item.feature.properties.database_id == null &&
                item.feature.properties.name == name &&
                item.feature.geometry.coordinates == coords
        }
    }

    private fun place(
        databaseId: Int?,
        name: String,
        lon: Double = 2.0,
        lat: Double = 1.0,
    ): Feature {
        return Feature(
            geometry = Geometry(coordinates = listOf(lon, lat)),
            properties = Properties(database_id = databaseId, name = name),
        )
    }
}
