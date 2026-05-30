package com.geovault.tracker.data

import com.geovault.tracker.GeoJsonLineString
import com.geovault.tracker.Tracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TrackerGeometryMergePolicyTest {

    @Test
    fun merged_whenNoExisting_returnsIncoming() {
        val incoming = tracker(
            id = "t1",
            name = "Incoming",
            geometryCoords = listOf(listOf(1.0, 2.0)),
            color = "#FF0000",
        )

        val merged = TrackerGeometryMergePolicy.merged(existing = null, incoming = incoming)

        assertEquals("t1", merged.id)
        assertEquals("Incoming", merged.name)
        assertEquals("#FF0000", merged.color)
        assertEquals(1, merged.geometry?.coordinates?.size)
    }

    @Test
    fun merged_preservesExistingMetadata_whenIncomingOmitsFields() {
        val existing = tracker(
            id = "t1",
            name = "Existing Name",
            geometryCoords = listOf(listOf(10.0, 20.0)),
            color = "#112233",
            ownerEmail = "owner@example.com",
            updatedAt = 100L,
            pointParams = listOf(mapOf("starttimestamp" to 90L)),
            lastPoint = listOf(10.0, 20.0, 100.0),
        )
        val incoming = tracker(
            id = "t1",
            name = "",
            geometryCoords = listOf(listOf(30.0, 40.0)),
            color = null,
            ownerEmail = null,
            updatedAt = null,
        )

        val merged = TrackerGeometryMergePolicy.merged(existing = existing, incoming = incoming)

        assertEquals("Existing Name", merged.name)
        assertEquals("#112233", merged.color)
        assertEquals("owner@example.com", merged.owner_email)
        assertEquals(100L, merged.updated_at)
        assertEquals(listOf(10.0, 20.0, 100.0), merged.last_point)
        assertNotNull(merged.geometry)
        assertEquals(listOf(listOf(30.0, 40.0)), merged.geometry?.coordinates)
        assertEquals(null, merged.point_params)
    }

    @Test
    fun merged_preservesExistingPointParams_whenIncomingOmitsGeometry() {
        val existing = tracker(
            id = "t1",
            name = "Existing Name",
            geometryCoords = listOf(listOf(10.0, 20.0)),
            color = "#112233",
            pointParams = listOf(mapOf("starttimestamp" to 90L)),
        )
        val incoming = tracker(
            id = "t1",
            name = "",
            geometryCoords = null,
            color = null,
        )

        val merged = TrackerGeometryMergePolicy.merged(existing = existing, incoming = incoming)

        assertEquals(listOf(listOf(10.0, 20.0)), merged.geometry?.coordinates)
        assertEquals(listOf(mapOf("starttimestamp" to 90L)), merged.point_params)
    }

    @Test
    fun merged_usesIncomingValues_whenProvided() {
        val existing = tracker(
            id = "t1",
            name = "Old",
            geometryCoords = listOf(listOf(1.0, 1.0)),
            color = "#000000",
            ownerEmail = "old@example.com",
            updatedAt = 10L,
            pointParams = listOf(mapOf("starttimestamp" to 1L)),
            lastPoint = listOf(1.0, 1.0, 10.0),
        )
        val incoming = tracker(
            id = "t1",
            name = "New",
            geometryCoords = listOf(listOf(2.0, 2.0)),
            color = "#ABCDEF",
            ownerEmail = "new@example.com",
            updatedAt = 20L,
            pointParams = listOf(mapOf("starttimestamp" to 2L)),
            lastPoint = listOf(2.0, 2.0, 20.0),
        )

        val merged = TrackerGeometryMergePolicy.merged(existing = existing, incoming = incoming)

        assertEquals("New", merged.name)
        assertEquals("#ABCDEF", merged.color)
        assertEquals("new@example.com", merged.owner_email)
        assertEquals(20L, merged.updated_at)
        assertEquals(listOf(mapOf("starttimestamp" to 2L)), merged.point_params)
        assertEquals(listOf(2.0, 2.0, 20.0), merged.last_point)
        assertEquals(listOf(listOf(2.0, 2.0)), merged.geometry?.coordinates)
    }

    private fun tracker(
        id: String,
        name: String,
        geometryCoords: List<List<Double>>?,
        color: String?,
        ownerEmail: String? = null,
        updatedAt: Long? = null,
        pointParams: List<Map<String, Any?>>? = null,
        lastPoint: List<Double>? = null,
    ): Tracker {
        return Tracker(
            id = id,
            name = name,
            color = color,
            settings = null,
            geometry = geometryCoords?.let { GeoJsonLineString(type = "LineString", coordinates = it) },
            point_params = pointParams,
            last_point = lastPoint,
            bbox = null,
            tracker_secret = null,
            created_at = null,
            subscribed_at = null,
            updated_at = updatedAt,
            is_owner = null,
            visibility = null,
            share_params_with_recipients = null,
            share_params_with_world = null,
            owner_email = ownerEmail,
            subscriber_count = null,
            world_share_id = null,
            world_share_url = null,
            shared_with_emails = null
        )
    }
}
