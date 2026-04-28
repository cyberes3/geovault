package com.geovault.places.data

import com.geovault.places.model.Feature
import com.geovault.places.model.Geometry
import com.geovault.places.model.OfflineFeature
import com.geovault.places.model.Properties
import org.junit.Assert.assertEquals
import org.junit.Test

class PlacesCacheStoreTest {

    @Test
    fun displayFeatures_offlineEditReplacesCachedFeatureWithSameDatabaseId() {
        val cached = listOf(
            place(databaseId = 7, name = "Cached"),
            place(databaseId = 8, name = "Other"),
        )
        val offline = listOf(
            OfflineFeature(feature = place(databaseId = 7, name = "Offline Edit")),
        )

        val display = PlacesCacheStore.computeDisplayFeatures(cached = cached, offline = offline)

        assertEquals(listOf("Offline Edit", "Other"), display.map { it.properties.name })
    }

    private fun place(databaseId: Int, name: String): Feature {
        return Feature(
            geometry = Geometry(coordinates = listOf(-105.0, 40.0)),
            properties = Properties(database_id = databaseId, name = name),
        )
    }
}
