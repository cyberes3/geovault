package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

import com.geovault.tracker.map.MapTrailEngine
class TrackerMapTrailLoaderTest {

    @Test
    fun loadLocalOverlay_singleServer_readsQueueWithoutServerFetch() {
        val currentServer = listOf(point("me", time = 1L))
        val queue = listOf(point("me", time = 2L, prov = MapTrailEngine.PROVENANCE_LOCAL_GPS))
        val plan = TrackerMapTrailReloadPlan(
            source = TrackerMapTrailSource.SINGLE_SERVER,
            singleTrackerId = "me",
            overlayTrackerId = "me",
            activeTrackerId = "me",
        )

        val loaded = runBlocking {
            MapTrailEngine.loadLocalOverlay(
                plan = plan,
                currentSingleTrail = currentServer,
                currentMultiTrails = emptyMap(),
                loadQueue = { id -> if (id == "me") queue else emptyList() },
            )
        }

        assertEquals(currentServer, loaded.singleTrailSeed)
        assertEquals(mapOf("me" to queue), loaded.queueOverlaysByTracker)
        assertTrue(loaded.serverTrails.isEmpty())
        assertTrue(loaded.authoritativeServerTrackerIds.isEmpty())
    }

    @Test
    fun loadLocalOverlay_emptyQueue_isOmittedFromOverlayMap() {
        val currentServer = listOf(point("me", time = 1L))
        val plan = TrackerMapTrailReloadPlan(
            source = TrackerMapTrailSource.SINGLE_SERVER,
            singleTrackerId = "me",
            overlayTrackerId = "me",
            activeTrackerId = "me",
        )

        val loaded = runBlocking {
            MapTrailEngine.loadLocalOverlay(
                plan = plan,
                currentSingleTrail = currentServer,
                currentMultiTrails = emptyMap(),
                loadQueue = { emptyList() },
            )
        }

        assertTrue("empty queue should not pollute the overlay map", loaded.queueOverlaysByTracker.isEmpty())
        assertEquals(currentServer, loaded.singleTrailSeed)
    }

    @Test
    fun loadLocalOverlay_noOverlayTracker_doesNotLoadQueue() {
        val currentMulti = mapOf("a" to listOf(point("a", time = 1L)))
        val plan = TrackerMapTrailReloadPlan(
            source = TrackerMapTrailSource.MULTI_SERVER,
            trackerIds = setOf("a"),
            overlayTrackerId = null,
            activeTrackerId = "a",
        )

        val loaded = runBlocking {
            MapTrailEngine.loadLocalOverlay(
                plan = plan,
                currentSingleTrail = emptyList(),
                currentMultiTrails = currentMulti,
                loadQueue = { error("queue must not be loaded when overlayTrackerId is null") },
            )
        }

        assertEquals(currentMulti, loaded.serverTrails)
        assertTrue(loaded.queueOverlaysByTracker.isEmpty())
    }

    @Test
    fun loadLocalOverlay_multiServer_keepsCurrentMultiTrailsAndQueueOverlay() {
        val ownerServer = listOf(
            point("me", time = 100L, prov = MapTrailEngine.PROVENANCE_SERVER_GEOMETRY),
            point("me", time = 110L, prov = MapTrailEngine.PROVENANCE_SERVER_GEOMETRY),
        )
        val peerServer = listOf(
            point("peer", time = 50L, prov = MapTrailEngine.PROVENANCE_SERVER_GEOMETRY),
        )
        val ownerQueue = listOf(
            point("me", time = 200L, prov = MapTrailEngine.PROVENANCE_LOCAL_GPS),
        )
        val currentMulti = mapOf("me" to ownerServer, "peer" to peerServer)
        val plan = TrackerMapTrailReloadPlan(
            source = TrackerMapTrailSource.MULTI_SERVER,
            trackerIds = setOf("me", "peer"),
            overlayTrackerId = "me",
            activeTrackerId = "me",
        )

        val loaded = runBlocking {
            MapTrailEngine.loadLocalOverlay(
                plan = plan,
                currentSingleTrail = ownerServer,
                currentMultiTrails = currentMulti,
                loadQueue = { id -> if (id == "me") ownerQueue else emptyList() },
            )
        }

        assertEquals(setOf("me", "peer"), loaded.serverTrails.keys)
        assertSame("server map for own tracker must reference the unmodified server list", ownerServer, loaded.serverTrails["me"])
        assertSame(peerServer, loaded.serverTrails["peer"])
        assertEquals(mapOf("me" to ownerQueue), loaded.queueOverlaysByTracker)
        assertEquals(ownerServer, loaded.singleTrailSeed)
        assertTrue(loaded.authoritativeServerTrackerIds.isEmpty())
    }

    private fun point(
        trackerId: String,
        time: Long,
        prov: String = MapTrailEngine.PROVENANCE_SERVER_GEOMETRY,
    ): QueuedLocation = QueuedLocation(
        id = time,
        trackerId = trackerId,
        time = time,
        latitude = time.toDouble(),
        longitude = time.toDouble(),
        altitude = null,
        speed = null,
        bearing = null,
        accuracy = null,
        sat = null,
        prov = prov,
        dist = null,
        startTimestampMs = null,
    )
}
