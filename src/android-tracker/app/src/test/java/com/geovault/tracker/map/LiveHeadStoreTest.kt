package com.geovault.tracker.map

import com.geovault.tracker.domain.TrackPoint
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.positioning.RecordingRuntime
import com.geovault.tracker.positioning.TrackingRuntimeSnapshot
import com.geovault.tracker.presentation.TrackerMapDisplayMode
import com.geovault.tracker.presentation.TrackerMapStreamingPlan
import com.geovault.tracker.presentation.TrackerMapTrailReloadPlan
import com.geovault.tracker.presentation.TrackerMapTrailSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveHeadStoreTest {

    @Test
    fun upsert_keepsNewerPointPerTracker() {
        val store = LiveHeadStore()
        store.upsert(remote("t1", timeMs = 1_000L, lat = 1.0))
        store.upsert(remote("t1", timeMs = 2_000L, lat = 2.0))
        store.upsert(remote("t1", timeMs = 1_500L, lat = 1.5))

        val head = store.value["t1"]
        assertEquals(2_000L, head?.timeMs)
        assertEquals(2.0, head?.latitude)
    }

    @Test
    fun replaceRemotes_keepsLocalGpsHead() {
        val store = LiveHeadStore()
        store.upsert(local("self", timeMs = 9_000L, lat = 9.0))
        store.upsert(remote("other", timeMs = 1_000L, lat = 1.0))

        store.replaceRemotes(mapOf("other" to remote("other", timeMs = 2_000L, lat = 2.0)))

        assertEquals(9_000L, store.value["self"]?.timeMs)
        assertEquals(2_000L, store.value["other"]?.timeMs)
    }

    @Test
    fun clearRemotes_keepsLocalGpsHead() {
        val store = LiveHeadStore()
        store.upsert(local("self", timeMs = 9_000L, lat = 9.0))
        store.upsert(remote("other", timeMs = 1_000L, lat = 1.0))

        store.clearRemotes()

        assertEquals(9_000L, store.value["self"]?.timeMs)
        assertNull(store.value["other"])
    }

    @Test
    fun syncRuntimeHead_writesLocalGpsAndDoesNotReplaceRemote() {
        val store = LiveHeadStore()
        store.upsert(remote("other", timeMs = 1_000L, lat = 1.0))
        MapTrailEngine.syncRuntimeHead(
            runtime = TrackingRuntimeSnapshot(
                recordingRuntime = RecordingRuntime(
                    sessionActive = true,
                    selectedTrackerId = "self",
                ),
                selectedTrackerId = "self",
                lastTrackedLatitude = 3.0,
                lastTrackedLongitude = 4.0,
                lastTrackedTimestampMs = 2_000L,
                lastAccuracyMeters = 5f,
                sessionStartTimeMs = 1_000L,
            ),
            plan = TrackerMapStreamingPlan(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                selectedTrackerId = "self",
                displayedTrackerId = "self",
                displayedTrackerName = "Self",
                resolvedGroupId = "",
                groupTrackerIds = emptySet(),
                visibleRosterTrackerIds = setOf("self"),
                locallyRecordedTrackerIds = setOf("self"),
                remoteSubscriptionIds = emptySet(),
                acceptedRemoteTrackerIds = emptySet(),
                localOverlayTrackerIds = setOf("self"),
                trailReloadPlan = TrackerMapTrailReloadPlan(
                    source = TrackerMapTrailSource.SINGLE_QUEUE,
                    activeTrackerId = "self",
                    singleTrackerId = "self",
                    trackerIds = setOf("self"),
                    overlayTrackerId = "self",
                ),
            ),
            liveHeads = store,
        )

        assertEquals(TrackPointSource.LOCAL_GPS, store.value["self"]?.provenance)
        assertEquals(2_000L, store.value["self"]?.timeMs)
        assertEquals(1_000L, store.value["other"]?.timeMs)
    }

    private fun remote(trackerId: String, timeMs: Long, lat: Double): TrackPoint {
        return TrackPoint(
            trackerId = trackerId,
            timeMs = timeMs,
            latitude = lat,
            longitude = 10.0,
            provenance = TrackPointSource.REMOTE_STREAM,
        )
    }

    private fun local(trackerId: String, timeMs: Long, lat: Double): TrackPoint {
        return TrackPoint(
            trackerId = trackerId,
            timeMs = timeMs,
            latitude = lat,
            longitude = 10.0,
            provenance = TrackPointSource.LOCAL_GPS,
        )
    }
}
