package com.geovault.places.data

import com.geovault.places.model.Feature
import com.geovault.places.model.Geometry
import com.geovault.places.model.Properties
import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceWriteBodyTest {
    private val omitNullsGson = GsonBuilder().create()

    @Test
    fun fromFeature_stripsResponseOnlyPropertiesAndBlankOptionalFields() {
        val feature = Feature(
            geometry = Geometry(coordinates = listOf(2.0, 1.0)),
            properties = Properties(
                database_id = 42,
                name = "  Camp  ",
                description = "  ",
                created_at = "2026-01-01",
                address = "  Trailhead  ",
            ),
        )

        val body = PlaceWriteBody.fromFeature(feature)

        assertEquals("Feature", body.type)
        assertEquals("Point", body.geometry.type)
        assertEquals(listOf(2.0, 1.0), body.geometry.coordinates)
        assertEquals("Camp", body.properties.name)
        assertNull(body.properties.description)
        assertEquals("Trailhead", body.properties.address)
    }

    @Test
    fun gsonWire_keysMatchFrontendPlacePayload() {
        val body = PlaceWriteBody.fromFeature(
            Feature(
                geometry = Geometry(coordinates = listOf(2.0, 1.0)),
                properties = Properties(
                    database_id = 7,
                    name = "Camp",
                    description = "Near water",
                    created_at = "2026-01-01",
                    address = "Trailhead",
                ),
            ),
        )

        val json = omitNullsGson.toJsonTree(body).asJsonObject
        assertEquals(setOf("type", "geometry", "properties"), json.keySet())
        val geometry = json.getAsJsonObject("geometry")
        assertEquals(setOf("type", "coordinates"), geometry.keySet())
        val properties = json.getAsJsonObject("properties")
        // Frontend placePayload: name, description, and address when non-blank.
        assertEquals(setOf("name", "description", "address"), properties.keySet())
        assertFalse(properties.has("database_id"))
        assertFalse(properties.has("created_at"))
    }

    @Test
    fun gsonWire_omitsNullOptionalFields() {
        val body = PlaceWriteBody.fromFeature(
            Feature(
                geometry = Geometry(coordinates = listOf(2.0, 1.0)),
                properties = Properties(name = "Camp", description = "  ", address = null),
            ),
        )
        val properties = omitNullsGson.toJsonTree(body).asJsonObject.getAsJsonObject("properties")
        assertEquals(setOf("name"), properties.keySet())
        assertTrue(properties.get("name").asString == "Camp")
    }

    @Test(expected = IllegalArgumentException::class)
    fun fromFeature_rejectsBlankName() {
        PlaceWriteBody.fromFeature(
            Feature(
                geometry = Geometry(coordinates = listOf(2.0, 1.0)),
                properties = Properties(name = "   "),
            ),
        )
    }
}
