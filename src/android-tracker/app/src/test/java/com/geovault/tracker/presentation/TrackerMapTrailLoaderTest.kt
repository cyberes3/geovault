package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapTrailLoaderTest {

    @Test
    fun load_singleServer_returnsSeedAndQueueOverlayWithoutClobberingServerMap() {
        val server = listOf(
            point("me", time = 100L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY),
        )
        val queue = listOf(
            point("me", time = 200L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS),
        )
        val ops = ops(
            singleServer = { id, _ -> if (id == "me") server else emptyList() },
            queue = { id -> if (id == "me") queue else emptyList() },
        )
        val plan = TrackerMapTrailReloadPlan(
            source = TrackerMapTrailSource.SINGLE_SERVER,
            singleTrackerId = "me",
            overlayTrackerId = "me",
            activeTrackerId = "me",
        )

        val loaded = runBlocking {
            TrackerMapTrailLoader.load(plan, existingTrailMinTimeMs = null, existingMultiMinTimes = emptyMap(), ops = ops)
        }

        assertEquals(server, loaded.singleTrailSeed)
        assertEquals(mapOf("me" to queue), loaded.queueOverlaysByTracker)
        assertTrue("server multi map should stay empty in SINGLE_SERVER", loaded.serverTrails.isEmpty())
    }

    @Test
    fun load_multiServer_keepsServerGeometryForOwnTrackerAndExposesQueueAsOverlay() {
        // MULTI_SERVER: server geometry for the locally-recorded tracker stays in serverTrails;
        // queue rows are exposed only via queueOverlaysByTracker so merge treats them as overlays.
        val ownerServer = listOf(
            point("me", time = 100L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY),
            point("me", time = 110L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY),
        )
        val peerServer = listOf(
            point("peer", time = 50L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY),
        )
        val ownerQueue = listOf(
            point("me", time = 200L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS),
        )
        val ops = ops(
            multiServer = { ids, _ ->
                ids.associateWith {
                    when (it) {
                        "me" -> ownerServer
                        "peer" -> peerServer
                        else -> emptyList()
                    }
                }
            },
            queue = { id -> if (id == "me") ownerQueue else emptyList() },
        )
        val plan = TrackerMapTrailReloadPlan(
            source = TrackerMapTrailSource.MULTI_SERVER,
            trackerIds = setOf("me", "peer"),
            overlayTrackerId = "me",
            activeTrackerId = "me",
        )

        val loaded = runBlocking {
            TrackerMapTrailLoader.load(plan, existingTrailMinTimeMs = null, existingMultiMinTimes = emptyMap(), ops = ops)
        }

        assertEquals(setOf("me", "peer"), loaded.serverTrails.keys)
        assertSame("server map for own tracker must reference the unmodified server list", ownerServer, loaded.serverTrails["me"])
        assertSame(peerServer, loaded.serverTrails["peer"])
        assertEquals(mapOf("me" to ownerQueue), loaded.queueOverlaysByTracker)
        assertEquals(ownerServer, loaded.singleTrailSeed)
    }

    @Test
    fun load_multiServer_noOverlayTracker_returnsEmptyQueueOverlays() {
        val server = mapOf("a" to listOf(point("a", time = 1L)))
        val ops = ops(
            multiServer = { _, _ -> server },
            queue = { error("queue must not be loaded when overlayTrackerId is null") },
        )
        val plan = TrackerMapTrailReloadPlan(
            source = TrackerMapTrailSource.MULTI_SERVER,
            trackerIds = setOf("a"),
            overlayTrackerId = null,
            activeTrackerId = "a",
        )

        val loaded = runBlocking {
            TrackerMapTrailLoader.load(plan, existingTrailMinTimeMs = null, existingMultiMinTimes = emptyMap(), ops = ops)
        }

        assertEquals(server, loaded.serverTrails)
        assertTrue(loaded.queueOverlaysByTracker.isEmpty())
    }

    @Test
    fun load_singleQueue_populatesOnlySingleTrailSeed() {
        val queue = listOf(point("me", time = 50L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS))
        val ops = ops(queue = { id -> if (id == "me") queue else emptyList() })
        val plan = TrackerMapTrailReloadPlan(
            source = TrackerMapTrailSource.SINGLE_QUEUE,
            activeTrackerId = "me",
        )

        val loaded = runBlocking {
            TrackerMapTrailLoader.load(plan, existingTrailMinTimeMs = null, existingMultiMinTimes = emptyMap(), ops = ops)
        }

        assertEquals(queue, loaded.singleTrailSeed)
        assertTrue(loaded.serverTrails.isEmpty())
        assertTrue(loaded.queueOverlaysByTracker.isEmpty())
    }

    @Test
    fun loadAndMerge_groupWithSelfWhileRecording_preservesOwnServerHistory() {
        // End-to-end MULTI_SERVER merge: recording user in a group must keep server history for
        // their tracker plus queue and in-flight live points as overlays.
        val ownerServer = listOf(
            point("me", time = 100L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY),
            point("me", time = 110L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY),
            point("me", time = 120L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY),
        )
        val peerServer = listOf(
            point("peer", time = 200L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY),
        )
        val ownerQueue = listOf(
            point("me", time = 150L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS),
        )
        val ownerLiveAppendsInState = listOf(
            point("me", time = 160L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS),
        )

        val ops = ops(
            multiServer = { _, _ -> mapOf("me" to ownerServer, "peer" to peerServer) },
            queue = { id -> if (id == "me") ownerQueue else emptyList() },
        )
        val plan = TrackerMapTrailReloadPlan(
            source = TrackerMapTrailSource.MULTI_SERVER,
            trackerIds = setOf("me", "peer"),
            overlayTrackerId = "me",
            activeTrackerId = "me",
        )

        val loaded = runBlocking {
            TrackerMapTrailLoader.load(plan, existingTrailMinTimeMs = null, existingMultiMinTimes = emptyMap(), ops = ops)
        }
        val merged = TrackerMapTrailMergePolicy.mergeServerTrailsWithLiveOverlays(
            serverTrails = loaded.serverTrails,
            currentTrails = mapOf("me" to ownerLiveAppendsInState),
            allowedLiveOverlayTrackerIds = plan.trackerIds + setOfNotNull(plan.overlayTrackerId),
            trailPointLimit = 100,
            extraLiveOverlaysByTracker = loaded.queueOverlaysByTracker,
        )

        assertEquals(setOf("me", "peer"), merged.keys)
        assertEquals(
            "own tracker keeps full server history with queue + live tail spliced on top",
            listOf(100L, 110L, 120L, 150L, 160L),
            merged.getValue("me").map { it.time },
        )
        assertEquals(listOf(200L), merged.getValue("peer").map { it.time })
    }

    private fun setOfNotNull(value: String?): Set<String> {
        return value?.let { setOf(it) }.orEmpty()
    }

    @Test
    fun load_emptyQueueResult_isOmittedFromOverlayMap() {
        val ops = ops(
            singleServer = { _, _ -> listOf(point("me", time = 1L)) },
            queue = { emptyList() },
        )
        val plan = TrackerMapTrailReloadPlan(
            source = TrackerMapTrailSource.SINGLE_SERVER,
            singleTrackerId = "me",
            overlayTrackerId = "me",
            activeTrackerId = "me",
        )

        val loaded = runBlocking {
            TrackerMapTrailLoader.load(plan, existingTrailMinTimeMs = null, existingMultiMinTimes = emptyMap(), ops = ops)
        }

        assertTrue("empty queue should not pollute the overlay map", loaded.queueOverlaysByTracker.isEmpty())
    }

    private fun ops(
        singleServer: suspend (String, Long?) -> List<QueuedLocation> = { _, _ -> emptyList() },
        multiServer: suspend (Collection<String>, Map<String, Long>) -> Map<String, List<QueuedLocation>> = { _, _ -> emptyMap() },
        queue: suspend (String) -> List<QueuedLocation> = { emptyList() },
    ): TrackerMapTrailLoaderOps = TrackerMapTrailLoaderOps(
        loadSingleServer = singleServer,
        loadMultiServer = multiServer,
        loadQueue = queue,
    )

    private fun point(
        trackerId: String,
        time: Long,
        prov: String = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY,
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
