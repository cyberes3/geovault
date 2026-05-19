package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapTrailCommitPolicyTest {

    @Test
    fun resolveSingleTrail_emptyServerMerge_usesPreReloadFilteredTrail() {
        val fallback = listOf(point(time = 10L), point(time = 20L))
        val resolved = TrackerMapTrailCommitPolicy.resolveSingleTrail(
            reloadReason = TrackerMapTrailReloadReason.RecentDataWindowChange,
            serverMergedTrail = emptyList(),
            preReloadFilteredTrail = fallback,
        )
        assertEquals(fallback, resolved)
    }

    @Test
    fun resolveSingleTrail_explicitLoad_emptyServer_preservesPreReload() {
        val fallback = listOf(point(time = 10L))
        val resolved = TrackerMapTrailCommitPolicy.resolveSingleTrail(
            reloadReason = TrackerMapTrailReloadReason.ExplicitTrackerLoad,
            serverMergedTrail = emptyList(),
            preReloadFilteredTrail = fallback,
        )
        assertEquals(fallback, resolved)
    }

    @Test
    fun resolveSingleTrail_filtersFallbackToActiveTracker() {
        val resolved = TrackerMapTrailCommitPolicy.resolveSingleTrail(
            reloadReason = TrackerMapTrailReloadReason.ExplicitTrackerLoad,
            serverMergedTrail = emptyList(),
            preReloadFilteredTrail = listOf(
                point(time = 10L, trackerId = "old"),
                point(time = 20L, trackerId = "new"),
            ),
            trackerId = "new",
        )
        assertEquals(listOf("new"), resolved.map { it.trackerId })
    }

    @Test
    fun resolveSingleTrail_mapContextChange_emptyServer_doesNotPreserve() {
        val resolved = TrackerMapTrailCommitPolicy.resolveSingleTrail(
            reloadReason = TrackerMapTrailReloadReason.MapContextChange,
            serverMergedTrail = emptyList(),
            preReloadFilteredTrail = listOf(point(time = 10L)),
        )
        assertTrue(resolved.isEmpty())
    }

    @Test
    fun resolveMultiTrails_emptyServerMerge_usesPerTrackerFallback() {
        val fallback = mapOf("t1" to listOf(point(time = 5L)))
        val resolved = TrackerMapTrailCommitPolicy.resolveMultiTrails(
            reloadReason = TrackerMapTrailReloadReason.RecentDataWindowChange,
            serverMergedTrails = mapOf("t1" to emptyList()),
            preReloadFilteredTrails = fallback,
            refreshedTrackerIds = setOf("t1"),
        )
        assertEquals(fallback["t1"], resolved["t1"])
    }

    @Test
    fun resolveMultiTrails_explicitLoad_usesFallbackForRefreshScope() {
        val fallback = mapOf("t1" to listOf(point(time = 5L)))
        val resolved = TrackerMapTrailCommitPolicy.resolveMultiTrails(
            reloadReason = TrackerMapTrailReloadReason.ExplicitTrackerLoad,
            serverMergedTrails = mapOf("t1" to emptyList()),
            preReloadFilteredTrails = fallback,
            refreshedTrackerIds = setOf("t1"),
        )
        assertEquals(fallback["t1"], resolved["t1"])
    }

    @Test
    fun expandQueueOverlays_loadsQueueForEachRefreshedTracker() = runBlocking {
        val overlays = TrackerMapTrailCommitPolicy.expandQueueOverlays(
            reloadReason = TrackerMapTrailReloadReason.RecentDataWindowChange,
            overlayTrackerIds = setOf("a", "b"),
            loadedOverlays = emptyMap(),
            loadQueue = { trackerId ->
                when (trackerId) {
                    "a" -> listOf(point(time = 1L))
                    "b" -> listOf(point(time = 2L))
                    else -> emptyList()
                }
            },
        )
        assertEquals(1, overlays["a"]?.size)
        assertEquals(1, overlays["b"]?.size)
    }

    @Test
    fun expandQueueOverlays_skipsForMetadataRefresh() = runBlocking {
        val overlays = TrackerMapTrailCommitPolicy.expandQueueOverlays(
            reloadReason = TrackerMapTrailReloadReason.MetadataMapRefresh,
            overlayTrackerIds = setOf("me"),
            loadedOverlays = emptyMap(),
            loadQueue = { error("should not load") },
        )
        assertTrue(overlays.isEmpty())
    }

    @Test
    fun shouldCapturePreReloadSnapshot_excludesMapContextChange() {
        assertFalse(TrackerMapTrailCommitPolicy.shouldCapturePreReloadSnapshot(
            TrackerMapTrailReloadReason.MapContextChange
        ))
        assertTrue(TrackerMapTrailCommitPolicy.shouldCapturePreReloadSnapshot(
            TrackerMapTrailReloadReason.ExplicitTrackerLoad
        ))
    }

    private fun point(time: Long, trackerId: String = "t1"): QueuedLocation {
        return QueuedLocation(
            id = time,
            trackerId = trackerId,
            time = time,
            latitude = 37.0,
            longitude = -122.0,
            altitude = null,
            speed = null,
            bearing = null,
            accuracy = null,
            sat = null,
            prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS,
            dist = null,
            startTimestampMs = 1_000L,
        )
    }
}
