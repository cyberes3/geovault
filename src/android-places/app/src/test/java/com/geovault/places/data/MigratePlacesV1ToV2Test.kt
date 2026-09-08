package com.geovault.places.data

import com.geovault.common.settings.GeoVaultLegacySettingsBlob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MigratePlacesV1ToV2Test {
    @Test
    fun offlineEditOverlaysCachedRowWithSameServerId() {
        val document = PlacesV1Converter.fromLists(
            cachedRaw = featureCollectionJson(id = 7, name = "Cached Camp"),
            offlineRaw = offlineJson(
                clientLocalId = "edit-7",
                id = 7,
                name = "Edited Camp",
                originalName = "Cached Camp",
            ),
            lastSyncMillis = 1_000L,
        )

        assertEquals(1, document.places.size)
        val row = document.places.single()
        assertEquals("s:7", row.key)
        assertEquals(7, row.serverId)
        assertEquals("Edited Camp", row.name)
        assertTrue(row.pending is PendingChangeDto.Update)
        assertEquals(1_000L, document.lastSyncMillis)
    }

    @Test
    fun blankClientLocalIdGetsUuidAndIsNotDropped() {
        val document = PlacesV1Converter.fromLists(
            cachedRaw = null,
            offlineRaw = offlineJson(clientLocalId = "   ", id = null, name = "Draft Camp"),
            lastSyncMillis = 0L,
        )

        assertEquals(1, document.places.size)
        val row = document.places.single()
        assertTrue(row.key.startsWith("l:"))
        assertNotEquals("l:", row.key)
        assertEquals("Draft Camp", row.name)
        assertEquals(PendingChangeDto.Create, row.pending)
    }

    @Test
    fun fromLegacyMapsOldBlobDirectlyToV2() {
        val blob = GeoVaultLegacySettingsBlob(
            stringValues = mapOf(
                "cached_places" to featureCollectionJson(id = 3, name = "Server Camp"),
                "offline_places" to offlineJson(clientLocalId = "new-1", id = null, name = "Local Camp"),
            ),
            longValues = mapOf("last_sync_time" to 42L),
        )

        val document = PlacesCacheDocument.fromLegacy(blob)
        assertEquals(2, document.places.size)
        assertEquals(setOf("s:3", "l:new-1"), document.places.map { it.key }.toSet())
        assertEquals(42L, document.lastSyncMillis)
    }

    @Test
    fun migrateJsonObjectPromotesV1Document() {
        val v1 = Json.parseToJsonElement(
            """
            {
              "cached": [${featureJson(id = 5, name = "A")}],
              "offline": [],
              "lastSyncMillis": 9
            }
            """.trimIndent(),
        ) as JsonObject

        val migrated = MigratePlacesV1ToV2.migrate(v1)
        val places = migrated["places"]?.jsonArray ?: JsonArray(emptyList())
        assertEquals(1, places.size)
        val decoded = Json.decodeFromJsonElement(PlacesCacheDocument.serializer(), migrated)
        assertEquals("A", decoded.places.single().name)
        assertEquals("s:5", decoded.places.single().key)
    }

    private fun featureCollectionJson(id: Int?, name: String): String {
        return """{"type":"FeatureCollection","features":[${featureJson(id, name)}]}"""
    }

    private fun featureJson(id: Int?, name: String, original: Boolean = false): String {
        val databaseId = if (id == null) "null" else id.toString()
        return """
            {
              "geometry": {"type":"Point","coordinates":[20.0,10.0]},
              "properties": {
                "database_id": $databaseId,
                "name": "$name",
                "description": "",
                "address": null
              }
            }
        """.trimIndent()
    }

    private fun offlineJson(
        clientLocalId: String,
        id: Int?,
        name: String,
        originalName: String? = null,
    ): String {
        val original = if (originalName != null && id != null) {
            ""","original": ${featureJson(id, originalName)}"""
        } else {
            ""
        }
        return """[{"clientLocalId":"$clientLocalId","feature":${featureJson(id, name)}$original}]"""
    }
}
