package com.geovault.places.data

import com.geovault.places.model.Feature
import com.geovault.places.model.Geometry
import com.geovault.places.model.OfflineFeature
import com.geovault.places.model.Properties
import org.junit.Assert.assertEquals
import org.junit.Test

class PlacesStoreDisplayTest {

    @Test
    fun displayFeatures_offlineEditReplacesCachedFeatureWithSameDatabaseId() {
        val cached = listOf(
            place(databaseId = 7, name = "Cached"),
            place(databaseId = 8, name = "Other"),
        )
        val offline = listOf(
            OfflineFeature(
                clientLocalId = "id-a",
                feature = place(databaseId = 7, name = "Offline Edit"),
            ),
        )

        val display = PlacesStore.computeDisplayFeatures(cached = cached, offline = offline)

        assertEquals(listOf("Offline Edit", "Other"), display.map { it.properties.name })
    }

    @Test
    fun displayFeatures_keepsUnsyncedCreatesAlongsideCached() {
        val cached = listOf(place(databaseId = 7, name = "Cached"))
        val offline = listOf(
            OfflineFeature(
                clientLocalId = "id-new",
                feature = place(databaseId = null, name = "Draft"),
            ),
        )

        val display = PlacesStore.computeDisplayFeatures(cached = cached, offline = offline)

        assertEquals(listOf("Draft", "Cached"), display.map { it.properties.name })
    }

    @Test
    fun retainValidOfflineEntries_keepsValidRowsAndDropsBlankIds() {
        val parsed = listOf(
            OfflineFeature(clientLocalId = "keep-me", feature = place(databaseId = null, name = "Draft")),
            OfflineFeature(clientLocalId = "", feature = place(databaseId = 7, name = "Legacy")),
            OfflineFeature(clientLocalId = "   ", feature = place(databaseId = 8, name = "Also Legacy")),
        )

        val retained = PlacesStore.retainValidOfflineEntries(parsed)

        assertEquals(listOf("keep-me"), retained.map { it.clientLocalId })
        assertEquals(listOf("Draft"), retained.map { it.feature.properties.name })
    }

    private fun place(databaseId: Int?, name: String): Feature {
        return Feature(
            geometry = Geometry(coordinates = listOf(2.0, 1.0)),
            properties = Properties(database_id = databaseId, name = name),
        )
    }
}
