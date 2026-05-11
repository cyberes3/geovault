package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TrackerMapTrailMergePolicyTest {

    @Test
    fun mergeServerTrailWithLiveOverlay_keepsOnlyAllowedNewerLivePoints() {
        val serverTrail = listOf(point("local", time = 100L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY))
        val currentTrail = listOf(
            point("local", time = 90L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS),
            point("local", time = 120L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS),
            point("other", time = 130L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS),
        )

        val merged = TrackerMapTrailMergePolicy.mergeServerTrailWithLiveOverlay(
            serverTrail = serverTrail,
            currentTrail = currentTrail,
            allowedLiveOverlayTrackerIds = setOf("local"),
            trailPointLimit = 10,
        )

        assertEquals(listOf(100L, 120L), merged.map { it.time })
        assertEquals(listOf("local", "local"), merged.map { it.trackerId })
    }

    @Test
    fun mergeServerTrailsWithLiveOverlays_dropsOutOfScopeCurrentTrackers() {
        val merged = TrackerMapTrailMergePolicy.mergeServerTrailsWithLiveOverlays(
            serverTrails = mapOf(
                "a" to listOf(point("a", time = 100L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY)),
            ),
            currentTrails = mapOf(
                "a" to listOf(point("a", time = 110L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_REMOTE_STREAM)),
                "old" to listOf(point("old", time = 120L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_REMOTE_STREAM)),
            ),
            allowedLiveOverlayTrackerIds = setOf("a"),
            trailPointLimit = 10,
        )

        assertEquals(setOf("a"), merged.keys)
        assertEquals(listOf(100L, 110L), merged.getValue("a").map { it.time })
        assertFalse(merged.containsKey("old"))
    }

    @Test
    fun mergeServerTrailWithLiveOverlay_localQueueAsServer_noNewerOverlay_preservesQueueHistory() {
        // RESTORE-AFTER-STREAMING REGRESSION GUARD (Bug 2): when restoring the selected (local)
        // tracker after a group stream stops, the freshly-loaded DB queue rows are fed into
        // mergeServerTrailWithLiveOverlay as the serverTrail. They are PROVENANCE_LOCAL_GPS
        // (which counts as live overlay), but with no newer live points the merge must short
        // circuit via `if (liveBuffer.isEmpty()) return serverTrail` and preserve the full
        // queue history rather than running the strip-isLiveOverlay path.
        val serverQueue = listOf(
            point("local", time = 50L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS),
            point("local", time = 60L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS),
            point("local", time = 70L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS),
        )

        val merged = TrackerMapTrailMergePolicy.mergeServerTrailWithLiveOverlay(
            serverTrail = serverQueue,
            currentTrail = emptyList(),
            allowedLiveOverlayTrackerIds = setOf("local"),
            trailPointLimit = 10,
        )

        assertEquals(listOf(50L, 60L, 70L), merged.map { it.time })
    }

    @Test
    fun mergeServerTrailWithLiveOverlay_serverGeometryWithLocalOverlay_appendsNewerLocalRows() {
        // RESTORE-AFTER-STREAMING POSITIVE GUARD (Bug 2): the standard SINGLE_SERVER path loads
        // PROVENANCE_SERVER_GEOMETRY rows for the displayed tracker and pairs them with the
        // local DB queue as live overlay candidates. Server rows must be preserved verbatim
        // (they are not live overlay), and any queue rows newer than the latest server time
        // must be stitched on top of the trail tail.
        val serverGeometry = listOf(
            point("local", time = 50L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY),
            point("local", time = 60L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY),
            point("local", time = 70L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY),
        )
        val liveOverlayCandidates = listOf(
            point("local", time = 65L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS),
            point("local", time = 80L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS),
        )

        val merged = TrackerMapTrailMergePolicy.mergeServerTrailWithLiveOverlay(
            serverTrail = serverGeometry,
            currentTrail = liveOverlayCandidates,
            allowedLiveOverlayTrackerIds = setOf("local"),
            trailPointLimit = 10,
        )

        assertEquals(listOf(50L, 60L, 70L, 80L), merged.map { it.time })
    }

    @Test
    fun mergeServerTrailWithLiveOverlay_dropsOverlayFromDifferentSession() {
        // SESSION-SAFE OVERLAY: a local-queue row stamped with a previous session's
        // startTimestampMs must NOT graft onto the active trail. Without this filter the
        // rendered line connects last-session's tail to this-session's head, producing the
        // "spike" the user reports (which restart fixes only because the queue clears).
        val activeStart = 5_000L
        val staleStart = 1_000L
        val serverGeometry = listOf(
            point("local", time = 100L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY, startTimestampMs = activeStart),
        )
        val liveOverlayCandidates = listOf(
            point("local", time = 200L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS, startTimestampMs = staleStart),
            point("local", time = 300L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS, startTimestampMs = activeStart),
        )

        val merged = TrackerMapTrailMergePolicy.mergeServerTrailWithLiveOverlay(
            serverTrail = serverGeometry,
            currentTrail = liveOverlayCandidates,
            allowedLiveOverlayTrackerIds = setOf("local"),
            trailPointLimit = 10,
            activeSessionStartMs = activeStart,
        )

        assertEquals(listOf(100L, 300L), merged.map { it.time })
        assertEquals(listOf(activeStart, activeStart), merged.map { it.startTimestampMs })
    }

    @Test
    fun mergeServerTrailWithLiveOverlay_keepsNullStartOverlayWhenSessionSet() {
        // Queue rows without session metadata (startTimestampMs == null) must still merge when
        // an active session is set; only non-null mismatching sessions are dropped.
        val activeStart = 5_000L
        val serverGeometry = listOf(
            point("local", time = 100L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY, startTimestampMs = activeStart),
        )
        val liveOverlayCandidates = listOf(
            point("local", time = 200L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS, startTimestampMs = null),
            point("local", time = 300L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS, startTimestampMs = activeStart),
        )

        val merged = TrackerMapTrailMergePolicy.mergeServerTrailWithLiveOverlay(
            serverTrail = serverGeometry,
            currentTrail = liveOverlayCandidates,
            allowedLiveOverlayTrackerIds = setOf("local"),
            trailPointLimit = 10,
            activeSessionStartMs = activeStart,
        )

        assertEquals(listOf(100L, 200L, 300L), merged.map { it.time })
    }

    @Test
    fun mergeServerTrailsWithLiveOverlays_filtersOnlyLocallyRecordingTrackerBySession() {
        // The multi-tracker variant filters by per-tracker active session: the locally
        // recording tracker drops cross-session overlay points; remote trackers (no entry
        // in activeSessionStartByTracker) pass through unfiltered, since we cannot infer
        // remote session boundaries from streamed points alone.
        val localActive = 5_000L
        val serverTrails = mapOf(
            "local" to listOf(point("local", time = 100L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY, startTimestampMs = localActive)),
            "remote" to listOf(point("remote", time = 100L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY, startTimestampMs = 9_000L)),
        )
        val currentTrails = mapOf(
            "local" to listOf(
                point("local", time = 200L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS, startTimestampMs = 1_000L),
                point("local", time = 300L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS, startTimestampMs = localActive),
            ),
            "remote" to listOf(
                point("remote", time = 250L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_REMOTE_STREAM, startTimestampMs = 9_000L),
            ),
        )

        val merged = TrackerMapTrailMergePolicy.mergeServerTrailsWithLiveOverlays(
            serverTrails = serverTrails,
            currentTrails = currentTrails,
            allowedLiveOverlayTrackerIds = setOf("local", "remote"),
            trailPointLimit = 10,
            activeSessionStartByTracker = mapOf("local" to localActive),
        )

        assertEquals(listOf(100L, 300L), merged.getValue("local").map { it.time })
        assertEquals(listOf(100L, 250L), merged.getValue("remote").map { it.time })
    }

    @Test
    fun mergeServerTrailsWithLiveOverlays_extraQueueOverlay_doesNotReplaceServerHistory() {
        // MULTI_SERVER: queue overlays must splice onto server geometry via
        // [extraLiveOverlaysByTracker], not replace the server trunk (which would strip under
        // filterNot(isLiveOverlay) and drop history from the group view).
        val serverGeometry = listOf(
            point("me", time = 50L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY),
            point("me", time = 60L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY),
            point("me", time = 70L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY),
        )
        val freshlyLoadedQueue = listOf(
            point("me", time = 80L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS),
            point("me", time = 90L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS),
        )

        val merged = TrackerMapTrailMergePolicy.mergeServerTrailsWithLiveOverlays(
            serverTrails = mapOf("me" to serverGeometry),
            currentTrails = emptyMap(),
            allowedLiveOverlayTrackerIds = setOf("me"),
            trailPointLimit = 10,
            extraLiveOverlaysByTracker = mapOf("me" to freshlyLoadedQueue),
        )

        assertEquals(setOf("me"), merged.keys)
        assertEquals(listOf(50L, 60L, 70L, 80L, 90L), merged.getValue("me").map { it.time })
    }

    @Test
    fun mergeServerTrailsWithLiveOverlays_extraQueueOverlay_combinesWithLiveAppendsInCurrentTrails() {
        // The reducer keeps appending live LOCAL_GPS rows into latest.allQueueTrailsByTracker
        // while the reload IO is in flight. Both the in-memory live appends (currentTrails)
        // AND the freshly-loaded DB queue (extraLiveOverlaysByTracker) need to splice on top
        // of server geometry, deduped by time-ordering.
        val serverGeometry = listOf(
            point("me", time = 50L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY),
        )
        val liveAppends = listOf(
            point("me", time = 90L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS),
        )
        val freshlyLoadedQueue = listOf(
            point("me", time = 70L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS),
        )

        val merged = TrackerMapTrailMergePolicy.mergeServerTrailsWithLiveOverlays(
            serverTrails = mapOf("me" to serverGeometry),
            currentTrails = mapOf("me" to liveAppends),
            allowedLiveOverlayTrackerIds = setOf("me"),
            trailPointLimit = 10,
            extraLiveOverlaysByTracker = mapOf("me" to freshlyLoadedQueue),
        )

        assertEquals(listOf(50L, 70L, 90L), merged.getValue("me").map { it.time })
    }

    private fun point(
        trackerId: String,
        time: Long,
        prov: String,
        startTimestampMs: Long? = null,
    ): QueuedLocation {
        return QueuedLocation(
            id = if (prov == TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY) -time else 0L,
            trackerId = trackerId,
            time = time,
            latitude = time.toDouble() / 1000.0,
            longitude = time.toDouble() / 1000.0,
            altitude = null,
            speed = null,
            bearing = null,
            accuracy = null,
            sat = null,
            prov = prov,
            dist = null,
            startTimestampMs = startTimestampMs,
        )
    }
}
