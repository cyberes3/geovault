package com.geovault.places.data

import com.geovault.places.samplePlace
import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceWriteBodyTest {
    private val omitNullsGson = GsonBuilder().create()

    @Test
    fun fromPlaceStripsResponseOnlyPropertiesAndBlankOptionalFields() {
        val body = PlaceWriteBody.fromPlace(
            samplePlace(
                serverId = 42,
                name = "  Camp  ",
                description = "  ",
                address = "  Trailhead  ",
                createdAt = "2026-01-01",
            ),
        )

        assertEquals("Feature", body.type)
        assertEquals("Point", body.geometry.type)
        assertEquals(listOf(20.0, 10.0), body.geometry.coordinates)
        assertEquals("Camp", body.properties.name)
        assertNull(body.properties.description)
        assertEquals("Trailhead", body.properties.address)
    }

    @Test
    fun gsonWireKeysMatchFrontendPlacePayload() {
        val body = PlaceWriteBody.fromPlace(
            samplePlace(
                serverId = 7,
                name = "Camp",
                description = "Near water",
                address = "Trailhead",
            ),
        )

        val json = omitNullsGson.toJsonTree(body).asJsonObject
        assertEquals(setOf("type", "geometry", "properties"), json.keySet())
        val geometry = json.getAsJsonObject("geometry")
        assertEquals(setOf("type", "coordinates"), geometry.keySet())
        val properties = json.getAsJsonObject("properties")
        assertEquals(setOf("name", "description", "address"), properties.keySet())
        assertFalse(properties.has("database_id"))
        assertFalse(properties.has("created_at"))
    }

    @Test
    fun gsonWireOmitsNullOptionalFields() {
        val body = PlaceWriteBody.fromPlace(samplePlace(name = "Camp", description = "  ", address = null))
        val properties = omitNullsGson.toJsonTree(body).asJsonObject.getAsJsonObject("properties")
        assertEquals(setOf("name"), properties.keySet())
        assertTrue(properties.get("name").asString == "Camp")
    }

    @Test
    fun gsonWireNewPlaceKeepsOnlyWriteKeys() {
        val body = PlaceWriteBody.fromPlace(
            samplePlace(
                key = com.geovault.places.model.PlaceKey.local("n"),
                serverId = null,
                name = "Test",
                description = "",
                createdAt = "2026-08-13",
                address = null,
            ),
        )

        val json = omitNullsGson.toJsonTree(body).asJsonObject
        val properties = json.getAsJsonObject("properties")
        assertEquals(setOf("name"), properties.keySet())
        assertEquals("Test", properties.get("name").asString)
        assertFalse(properties.has("database_id"))
        assertFalse(properties.has("created_at"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun fromPlaceRejectsBlankName() {
        PlaceWriteBody.fromPlace(samplePlace(name = "   "))
    }
}
